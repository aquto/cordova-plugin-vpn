/*
 * Copyright (C) 2014-2015 Paul Kinsky
 * Copyright (C) 2012-2013 Tobias Brunner
 * Copyright (C) 2012 Giuliano Grassi
 * Copyright (C) 2012 Ralf Sager
 * Hochschule fuer Technik Rapperswil
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */
package org.strongswan.android.logic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;


import org.aquto.cordova.vpn.VpnProfile;
import org.aquto.cordova.vpn.VpnType;
import org.aquto.cordova.vpn.ImcState;
import org.aquto.cordova.vpn.RemediationInstruction;


import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;

import android.os.CountDownTimer;

public class CharonVpnService extends VpnService implements Runnable
{
	public static final String STOP_REASON = "stopReason";
	public static final String DISALLOWED_NETWORK_STOP_REASON = "disallowedNetwork";
	public static final String MANUAL_STOP_REASON = "disallowedNetwork";
	public static String keystoreFile = "aquto_vpn_keystore";
	public static String keystorePass = "aquto!";

	private static final String TAG = CharonVpnService.class.getSimpleName();
	public static final String LOG_FILE = "charon.log";


	public enum State
	{
		DISABLED,
		CONNECTING,
		CONNECTED,
		DISCONNECTING,
	}

	public enum ErrorState
	{
		NO_ERROR,
		AUTH_FAILED,
		PEER_AUTH_FAILED,
		LOOKUP_FAILED,
		UNREACHABLE,
		GENERIC_ERROR,
		DISALLOWED_NETWORK_TYPE,
		TIMEOUT
	}

	private String mLogFile;
	private Thread mConnectionHandler;
	private VpnProfile mCurrentProfile;
	private VpnProfile mNextProfile;
	private volatile boolean mProfileUpdated;
	private volatile boolean mTerminate;
	private volatile boolean mIsDisconnecting;

	/**
	 * as defined in charonservice.h
	 */
	static final int STATE_CHILD_SA_UP = 1;
	static final int STATE_CHILD_SA_DOWN = 2;
	static final int STATE_AUTH_ERROR = 3;
	static final int STATE_PEER_AUTH_ERROR = 4;
	static final int STATE_LOOKUP_ERROR = 5;
	static final int STATE_UNREACHABLE_ERROR = 6;
	static final int STATE_GENERIC_ERROR = 7;


	private static CallbackContext callback = null;

	private static CountDownTimer timeout = null;

	//synchronized because callbacks are unlikely to be thread safe
	public synchronized static void registerCallback(CallbackContext callback){
		CharonVpnService.callback = callback;
	}

	//synchronized because callbacks are unlikely to be thread safe
	private synchronized static void onStateChange(State newState){
		Log.d(TAG, "onStateChange: " + newState + " at time " + System.currentTimeMillis());
		if (callback != null){
			PluginResult pr = new PluginResult(PluginResult.Status.OK, ""+newState);
			pr.setKeepCallback(true);
			callback.sendPluginResult(pr);
		}
		//cancel timeout if connection succeeds
		if (timeout != null && newState == State.CONNECTED){
			Log.d(TAG, "cancel timeout on connected state change");
			timeout.cancel();
		}
	}

	//synchronized because callbacks are unlikely to be thread safe
	private synchronized static void onErrorStateChange(ErrorState newErrorState){
		if (callback != null){
			Log.d(TAG, "onerrorStateChange: " + newErrorState);
			PluginResult pr = new PluginResult(PluginResult.Status.ERROR, ""+newErrorState);
			pr.setKeepCallback(true);
			callback.sendPluginResult(pr);
			//wipe out callback so that only the first error is sent
			callback = null;
		}
	}


	private KeyStore _keystore = null;
	private KeyStore getKeyStore()
	{
		try{
		if (_keystore == null)
		{
			_keystore = KeyStore.getInstance("PKCS12");
			InputStream fis = openFileInput(keystoreFile);
			_keystore.load(fis, keystorePass.toCharArray());
			fis.close();
			Log.d(TAG, "loaded keystore");
		}
		return _keystore;
		}catch (Exception e) {
			return null;
		}

	}


	private void startTimeoutTimer(long millis){

		CountDownTimer timer = new CountDownTimer(millis, millis) {
			public void onTick(long millisUntilFinished) {
			 //no-op
			}

			public void onFinish() {
			 Log.d(TAG, "timeout timer finish at " + System.currentTimeMillis());
			 onErrorStateChange(ErrorState.TIMEOUT);
			 //kill current connection
			 setNextProfile(null);
			}
		};

		Log.d(TAG, "timeout timer (" + millis + ") started at " + System.currentTimeMillis());


		timer.start();
		timeout = timer;
	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		if (intent != null)
		{
			if (intent.hasExtra(STOP_REASON)){
				if (intent.getStringExtra(STOP_REASON).equals(DISALLOWED_NETWORK_STOP_REASON)){
					setError(ErrorState.DISALLOWED_NETWORK_TYPE);
				}

				Log.d(TAG, "next profile: null (kill connection)");
				setNextProfile(null);
			} else {

				VpnProfile profile = VpnProfile.fromBundle(intent.getExtras());
				if (profile != null){
					Log.d(TAG, "charon: connect using vpn profile " + profile);
				}
				setNextProfile(profile);
				startTimeoutTimer(profile.vpnConnectionTimeoutMillis);
			}
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onCreate()
	{

		mLogFile = getFilesDir().getAbsolutePath() + File.separator + LOG_FILE;

		/* use a separate thread as main thread for charon */
		mConnectionHandler = new Thread(this);
								mConnectionHandler.start();
		/* the thread is started when the service is bound */
	}

	@Override
	public void onRevoke()
	{  /* the system revoked the rights grated with the initial prepare() call.
		 * called when the user clicks disconnect in the system's VPN dialog */
		setNextProfile(null);
	}

	@Override
	public void onDestroy()
	{
		mTerminate = true;
		setNextProfile(null);
		try
		{
			mConnectionHandler.join();
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}
	}

	/**
	 * Set the profile that is to be initiated next. Notify the handler thread.
	 *
	 * @param profile the profile to initiate
	 */
	private void setNextProfile(VpnProfile profile)
	{
		synchronized (this)
		{
			this.mNextProfile = profile;
			mProfileUpdated = true;
			notifyAll();
		}
	}

	@Override
	public void run()
	{
		while (true)
		{

			synchronized (this)
			{
				try
				{
					while (!mProfileUpdated)
					{
						wait();
					}


					mProfileUpdated = false;
					stopCurrentConnection();

					// needed to confirm we're in an allowed connection state before connecting
					ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
					NetworkInfo info = cm.getActiveNetworkInfo();
					int mobileOnlyId = getResources().getIdentifier("mobile_only", "bool", getPackageName());
					boolean mobileOnly = getResources().getBoolean(mobileOnlyId);

					if (mNextProfile == null)
					{
						setState(State.DISABLED);
						if (mTerminate)
						{
							break;
						}
					}
					else if (mobileOnly && !NetworkManager.connectionValid(info)){
						// handles the edge case where we connect to wifi after
						// an intent to connect has been sent but before connection occurs
						setError(ErrorState.DISALLOWED_NETWORK_TYPE);
					} else {

						mCurrentProfile = mNextProfile;
						mNextProfile = null;

						startConnection(mCurrentProfile);
						mIsDisconnecting = false;

						BuilderAdapter builder = new BuilderAdapter(mCurrentProfile.name);
						Boolean initRes = initializeCharon(builder, mLogFile, mCurrentProfile.vpnType.getEnableBYOD());
						if (initRes)
						{
							onStateChange(State.CONNECTING);
							Log.i(TAG, "charon started");
							initiate(mCurrentProfile.vpnType.getIdentifier(),
									 mCurrentProfile.gateway, mCurrentProfile.username,
									 mCurrentProfile.password);
						}
						else
						{
							Log.e(TAG, "failed to start charon");
							setError(ErrorState.GENERIC_ERROR);
							setState(State.DISABLED);
							mCurrentProfile = null;
						}
					}
				}
				catch (InterruptedException ex)
				{
					stopCurrentConnection();
					setState(State.DISABLED);
				}
			}
		}
	}

	/**
	 * Stop any existing connection by deinitializing charon.
	 */
	private void stopCurrentConnection()
	{
		synchronized (this)
		{
			if (mCurrentProfile != null)
			{
				setState(State.DISCONNECTING);
				mIsDisconnecting = true;
				deinitializeCharon();
				Log.i(TAG, "charon stopped");
				mCurrentProfile = null;
			}
		}
	}

	/**
	 * Notify the state service about a new connection attempt.
	 * Called by the handler thread.
	 *
	 * @param profile currently active VPN profile
	 */
	private void startConnection(VpnProfile profile)
	{
		// no-op
	}

	/**
	 * Update the current VPN state on the state service. Called by the handler
	 * thread and any of charon's threads.
	 *
	 * @param state current state
	 */
	private void setState(State state)
	{
		onStateChange(state);
	}

	/**
	 * Set an error on the state service. Called by the handler thread and any
	 * of charon's threads.
	 *
	 * @param error error state
	 */
	private void setError(ErrorState error)
	{
		onErrorStateChange(error);
	}

	/**
	 * Set the IMC state on the state service. Called by the handler thread and
	 * any of charon's threads.
	 *
	 * @param state IMC state
	 */
	private void setImcState(ImcState state)
	{
		//no-op
	}

	/**
	 * Set an error on the state service. Called by the handler thread and any
	 * of charon's threads.
	 *
	 * @param error error state
	 */
	private void setErrorDisconnect(ErrorState error)
	{
		onErrorStateChange(error);
	}

	/**
	 * Updates the state of the current connection.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param status new state
	 */
	public void updateStatus(int status)
	{
		switch (status)
		{
			case STATE_CHILD_SA_DOWN:
				if (!mIsDisconnecting)
				{
					setState(State.CONNECTING);
				}
				break;
			case STATE_CHILD_SA_UP:
				setState(State.CONNECTED);
				break;
			case STATE_AUTH_ERROR:
				setErrorDisconnect(ErrorState.AUTH_FAILED);
				break;
			case STATE_PEER_AUTH_ERROR:
				setErrorDisconnect(ErrorState.PEER_AUTH_FAILED);
				break;
			case STATE_LOOKUP_ERROR:
				setErrorDisconnect(ErrorState.LOOKUP_FAILED);
				break;
			case STATE_UNREACHABLE_ERROR:
				setErrorDisconnect(ErrorState.UNREACHABLE);
				break;
			case STATE_GENERIC_ERROR:
				setErrorDisconnect(ErrorState.GENERIC_ERROR);
				break;
			default:
				Log.e(TAG, "Unknown status code received");
				break;
		}
	}

	/**
	 * Updates the IMC state of the current connection.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param value new state
	 */
	public void updateImcState(int value)
	{
		ImcState state = ImcState.fromValue(value);
		if (state != null)
		{
			setImcState(state);
		}
	}

	/**
	 * Add a remediation instruction to the VPN state service.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param xml XML text
	 */
	public void addRemediationInstruction(String xml)
	{
			Log.d(TAG, "add remediation instruction: " + xml);
	}

	/**
	 * Function called via JNI to generate a list of DER encoded CA certificates
	 * as byte array.
	 *
	 * @return a list of DER encoded CA certificates
	 */
	private byte[][] getTrustedCertificates(String aliasIn)
	{
		return new byte[][] {};
	}

	/**
	 * Function called via JNI to get a list containing the DER encoded certificates
	 * of the user selected certificate chain (beginning with the user certificate).
	 *
	 * Since this method is called from a thread of charon's thread pool we are safe
	 * to call methods on KeyChain directly.
	 *
	 * @return list containing the certificates (first element is the user certificate)
	 * @throws Exception
	 * @throws KeyChainException
	 */
	private byte[][] getUserCertificate() throws Exception
	{
		try{
			Certificate[] certchain = getKeyStore().getCertificateChain(mCurrentProfile.alias);

			byte[][] res = new byte[certchain.length][];

			for (int i = 0; i < certchain.length; i++){
				res[i] = certchain[i].getEncoded();
			}

			return res;
		} catch (Exception e) {
			Log.e(TAG, "cert load failed w/ " + e);
			throw e;
		}
	}


	/**
	 * Function called via JNI to get the private key the user selected.
	 *
	 * Since this method is called from a thread of charon's thread pool we are safe
	 * to call methods on KeyChain directly.
	 *
	 * @return the private key
	 * @throws Exception
	 * @throws CertificateEncodingException
	 */
	private PrivateKey getUserKey() throws Exception
	{
		try{
			PrivateKey priv = (PrivateKey)getKeyStore().getKey(mCurrentProfile.alias, keystorePass.toCharArray());
			return priv;
		} catch (Exception ex) {
			Log.e(TAG, "failed to create keys due to " + ex);
			throw ex;
		}
	}

	/**
	 * Initialization of charon, provided by libandroidbridge.so
	 *
	 * @param builder BuilderAdapter for this connection
	 * @param logfile absolute path to the logfile
	 * @param boyd enable BYOD features
	 * @return TRUE if initialization was successful
	 */
	public native boolean initializeCharon(BuilderAdapter builder, String logfile, boolean byod);

	/**
	 * Deinitialize charon, provided by libandroidbridge.so
	 */
	public native void deinitializeCharon();

	/**
	 * Initiate VPN, provided by libandroidbridge.so
	 */
	public native void initiate(String type, String gateway, String username, String password);

	/**
	 * Adapter for VpnService.Builder which is used to access it safely via JNI.
	 * There is a corresponding C object to access it from native code.
	 */
	public class BuilderAdapter
	{
		private final String mName;
		private VpnService.Builder mBuilder;
		private BuilderCache mCache;
		private BuilderCache mEstablishedCache;

		public BuilderAdapter(String name)
		{
			mName = name;
			mBuilder = createBuilder(name);
			mCache = new BuilderCache();
		}

		private VpnService.Builder createBuilder(String name)
		{
			VpnService.Builder builder = new CharonVpnService.Builder();
			builder.setSession(mName);

			//not setting configure intent.
			return builder;
		}

		public synchronized boolean addAddress(String address, int prefixLength)
		{
			try
			{
				mBuilder.addAddress(address, prefixLength);
				mCache.addAddress(address, prefixLength);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addDnsServer(String address)
		{
			try
			{
				mBuilder.addDnsServer(address);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addRoute(String address, int prefixLength)
		{
			try
			{
				mBuilder.addRoute(address, prefixLength);
				mCache.addRoute(address, prefixLength);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addSearchDomain(String domain)
		{
			try
			{
				mBuilder.addSearchDomain(domain);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean setMtu(int mtu)
		{
			try
			{
				mBuilder.setMtu(mtu);
				mCache.setMtu(mtu);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized int establish()
		{
			ParcelFileDescriptor fd;
			try
			{
				fd = mBuilder.establish();
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				return -1;
			}
			if (fd == null)
			{
				return -1;
			}
			/* now that the TUN device is created we don't need the current
			 * builder anymore, but we might need another when reestablishing */
			mBuilder = createBuilder(mName);
			mEstablishedCache = mCache;
			mCache = new BuilderCache();
			return fd.detachFd();
		}

		public synchronized int establishNoDns()
		{
			ParcelFileDescriptor fd;

			if (mEstablishedCache == null)
			{
				return -1;
			}
			try
			{
				Builder builder = createBuilder(mName);
				mEstablishedCache.applyData(builder);
				fd = builder.establish();
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				return -1;
			}
			if (fd == null)
			{
				return -1;
			}
			return fd.detachFd();
		}
	}

	/**
	 * Cache non DNS related information so we can recreate the builder without
	 * that information when reestablishing IKE_SAs
	 */
	public class BuilderCache
	{
		private final List<PrefixedAddress> mAddresses = new ArrayList<PrefixedAddress>();
		private final List<PrefixedAddress> mRoutes = new ArrayList<PrefixedAddress>();
		private int mMtu;

		public void addAddress(String address, int prefixLength)
		{
			mAddresses.add(new PrefixedAddress(address, prefixLength));
		}

		public void addRoute(String address, int prefixLength)
		{
			mRoutes.add(new PrefixedAddress(address, prefixLength));
		}

		public void setMtu(int mtu)
		{
			mMtu = mtu;
		}

		public void applyData(VpnService.Builder builder)
		{
			for (PrefixedAddress address : mAddresses)
			{
				builder.addAddress(address.mAddress, address.mPrefix);
			}
			for (PrefixedAddress route : mRoutes)
			{
				builder.addRoute(route.mAddress, route.mPrefix);
			}
			builder.setMtu(mMtu);
		}

		private class PrefixedAddress
		{
			public String mAddress;
			public int mPrefix;

			public PrefixedAddress(String address, int prefix)
			{
				this.mAddress = address;
				this.mPrefix = prefix;
			}
		}
	}

	static
	{
		System.loadLibrary("strongswan");

		System.loadLibrary("hydra");
		System.loadLibrary("charon");
		System.loadLibrary("ipsec");
		System.loadLibrary("androidbridge");
	}
}
