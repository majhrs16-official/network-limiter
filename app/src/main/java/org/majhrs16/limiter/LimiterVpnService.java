package org.majhrs16.limiter;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import org.majhrs16.limiter.core.Diag;
import org.majhrs16.limiter.core.Format;
import org.majhrs16.limiter.core.Stats;
import org.majhrs16.limiter.core.TunnelEngine;
import org.majhrs16.limiter.core.VpnConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;

/**
 * Servicio VPN en primer plano.
 *
 * <p>Mantiene la notificación permanente y un wake lock parcial mientras el
 * túnel está activo para que el planificador no duerma el proceso y el bucle
 * de paquetes siga respondiendo.</p>
 */
public class LimiterVpnService extends VpnService {

	private static final String TAG = "LimiterVpnService";

	public static final String ACTION_START = "org.majhrs16.limiter.action.START";
	public static final String ACTION_STOP = "org.majhrs16.limiter.action.STOP";
	public static final String ACTION_UPDATE = "org.majhrs16.limiter.action.UPDATE";
	public static final String ACTION_RESTART = "org.majhrs16.limiter.action.RESTART";

	private static final String CHANNEL_ID = "limiter_vpn";
	private static final int NOTIFICATION_ID = 4101;

	private static final Stats STATS = new Stats();
	private static volatile boolean running;

	private ParcelFileDescriptor tun;
	private volatile TunnelEngine engine;
	private PowerManager.WakeLock wakeLock;
	private Handler handler;
	private volatile boolean foreground;

	private final Runnable notifier = new Runnable() {
		@Override
		public void run() {
			if (!running) {
				return;
			}
			updateNotification();
			if (handler != null) {
				handler.postDelayed(this, 2000);
			}
		}
	};

	public static boolean isRunning() {
		return running;
	}

	public static Stats stats() {
		return STATS;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Diag.setSink(new Diag.Sink() {
			@Override
			public void log(char level, String tag, String message, Throwable error) {
				switch (level) {
					case 'E':
						Log.e(tag, message, error);
						break;
					case 'W':
						Log.w(tag, message, error);
						break;
					case 'I':
						Log.i(tag, message);
						break;
					default:
						Log.d(tag, message);
						break;
				}
			}
		});
		handler = new Handler(Looper.getMainLooper());
		createChannel();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String action = intent == null ? null : intent.getAction();

		if (ACTION_STOP.equals(action)) {
			stopVpn();
			stopSelf();
			return START_NOT_STICKY;
		}

		if (ACTION_RESTART.equals(action)) {
			/* El split tunneling se fija al construir la interfaz TUN, así que
			 * un cambio de apps obliga a recrearla. */
			stopVpn();
			startVpn();
			return START_STICKY;
		}

		if (ACTION_UPDATE.equals(action)) {
			if (!running && engine == null) {
				/* Actualización huérfana: no dejar el servicio vivo sin
				 * notificación de primer plano. */
				stopSelf();
				return START_NOT_STICKY;
			}
			VpnConfig config = VpnConfig.load(this);
			if (engine != null) {
				engine.updateConfig(config);
			}
			updateNotification();
			return START_STICKY;
		}

		startVpn();
		return START_STICKY;
	}

	@Override
	public void onRevoke() {
		Diag.w(TAG, "Permiso VPN revocado por el sistema");
		stopVpn();
		stopSelf();
		super.onRevoke();
	}

	@Override
	public void onDestroy() {
		stopVpn();
		super.onDestroy();
	}

	/* ------------------------------------------------------------------ */

	private synchronized void startVpn() {
		if (running) {
			updateNotification();
			return;
		}

		VpnConfig config = VpnConfig.load(this);
		STATS.reset();
		createChannel();
		try {
			startForeground(NOTIFICATION_ID, buildNotification());
		} catch (Exception e) {
			Diag.e(TAG, "No se pudo pasar el servicio a primer plano", e);
			stopSelf();
			return;
		}
		foreground = true;
		acquireWakeLock();

		Builder builder = new Builder();
		builder.setSession("Network Limiter");
		builder.setMtu(config.mtu);
		builder.setBlocking(true);
		builder.addAddress("10.111.0.1", 32);
		builder.addRoute("0.0.0.0", 0);
		builder.addAddress("fd00:1111::1", 128);
		builder.addRoute("::", 0);
		addDnsServers(builder);

		applySplitTunnel(builder, config);

		try {
			tun = builder.establish();
		} catch (Exception e) {
			Diag.e(TAG, "No se pudo crear la interfaz TUN", e);
			stopVpn();
			stopSelf();
			return;
		}

		if (tun == null) {
			Diag.e(TAG, "VpnService.Builder.establish() devolvió null", null);
			stopVpn();
			stopSelf();
			return;
		}

		STATS.reset();
		engine = new TunnelEngine(tun, new TunnelEngine.Protector() {
			@Override
			public boolean protectSocket(SocketChannel channel) {
				return LimiterVpnService.this.protect(channel.socket());
			}

			@Override
			public boolean protectDatagram(DatagramChannel channel) {
				return LimiterVpnService.this.protect(channel.socket());
			}
		}, config, STATS);

		try {
			engine.start();
		} catch (IOException e) {
			Diag.e(TAG, "No se pudo iniciar el motor", e);
			stopVpn();
			stopSelf();
			return;
		}

		running = true;
		handler.removeCallbacks(notifier);
		handler.postDelayed(notifier, 1000);
		Diag.i(TAG, "VPN activa");
	}

	private synchronized void stopVpn() {
		running = false;
		if (handler != null) {
			handler.removeCallbacks(notifier);
		}
		if (engine != null) {
			engine.stop();
			engine = null;
		}
		if (tun != null) {
			try {
				tun.close();
			} catch (IOException ignored) {
				/* nada */
			}
			tun = null;
		}
		releaseWakeLock();

		if (foreground) {
			stopForeground(STOP_FOREGROUND_REMOVE);
			foreground = false;
		}
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (nm != null) {
			nm.cancel(NOTIFICATION_ID);
		}
		Diag.i(TAG, "VPN detenida");
	}

	/**
	 * Android no permite mezclar listas de apps permitidas y denegadas.
	 * <ul>
	 *   <li>Todas: se excluye solo la propia app para que su UI no se enrute.</li>
	 *   <li>Solo seleccionadas: lista permitida; si queda vacía se enrutan todas.</li>
	 *   <li>Todas excepto seleccionadas: lista denegada.</li>
	 * </ul>
	 * La app propia nunca entra en la VPN: necesita la red real para mostrar
	 * métricas y para que el sistema pueda revocar el permiso.
	 */
	private void applySplitTunnel(Builder builder, VpnConfig config) {
		String self = getPackageName();
		if (config.splitMode == VpnConfig.SPLIT_INCLUDE) {
			int added = 0;
			for (String pkg : config.splitPackages) {
				if (self.equals(pkg)) {
					continue;
				}
				if (addAllowedApplication(builder, pkg)) {
					added++;
				}
			}
			if (added > 0) {
				return;
			}
			/* Modo inclusivo sin apps válidas: se comporta como "todas". */
		}

		addDisallowedApplication(builder, self);
		if (config.splitMode == VpnConfig.SPLIT_EXCLUDE) {
			for (String pkg : config.splitPackages) {
				if (!self.equals(pkg)) {
					addDisallowedApplication(builder, pkg);
				}
			}
		}
	}

	private static boolean addAllowedApplication(Builder builder, String pkg) {
		try {
			builder.addAllowedApplication(pkg);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			Diag.w(TAG, "App no instalada en la lista permitida: " + pkg, null);
			return false;
		}
	}

	private static void addDisallowedApplication(Builder builder, String pkg) {
		try {
			builder.addDisallowedApplication(pkg);
		} catch (PackageManager.NameNotFoundException e) {
			Diag.w(TAG, "App no instalada en la lista denegada: " + pkg, null);
		}
	}

	/* --------------------------- notificación -------------------------- */

	private void createChannel() {
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (nm == null) {
			return;
		}
		NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
				getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
		channel.setDescription(getString(R.string.channel_description));
		channel.setShowBadge(false);
		channel.enableVibration(false);
		channel.setSound(null, null);
		nm.createNotificationChannel(channel);
	}

	private Notification buildNotification() {
		Stats.Snapshot snapshot = STATS.snapshot();
		String text = "↓ " + Format.rate(snapshot.downRate)
				+ " · ↑ " + Format.rate(snapshot.upRate);

		Intent openIntent = new Intent(this, MainActivity.class)
				.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		PendingIntent content = PendingIntent.getActivity(this, 0, openIntent,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

		Intent stopIntent = new Intent(this, LimiterVpnService.class)
				.setAction(ACTION_STOP);
		PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

		Notification.Action action = new Notification.Action.Builder(
				Icon.createWithResource(this, R.drawable.ic_stop),
				getString(R.string.action_stop), stop).build();

		Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_stat_limiter)
				.setContentTitle(getString(R.string.notification_title))
				.setContentText(text)
				.setContentIntent(content)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setAutoCancel(false)
				.setShowWhen(false)
				.setCategory(Notification.CATEGORY_SERVICE)
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.addAction(action);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
		}
		return builder.build();
	}

	private void updateNotification() {
		if (!running) {
			return;
		}
		NotificationManager nm = getSystemService(NotificationManager.class);
		if (nm != null) {
			nm.notify(NOTIFICATION_ID, buildNotification());
		}
	}

	/* ----------------------------- utilidades -------------------------- */

	private void addDnsServers(Builder builder) {
		boolean added = false;
		try {
			ConnectivityManager cm = getSystemService(ConnectivityManager.class);
			Network active = cm == null ? null : cm.getActiveNetwork();
			LinkProperties properties = active == null ? null : cm.getLinkProperties(active);
			if (properties != null) {
				int count = 0;
				for (InetAddress dns : properties.getDnsServers()) {
					if (count >= 4) {
						break;
					}
					/* Un DNS link-local/multicast no es enrutable por el
					 * túnel (necesita scope de interfaz) y el motor lo
					 * filtraría; es mejor no anunciarlo. */
					if (dns.isLinkLocalAddress() || dns.isLoopbackAddress()
							|| dns.isAnyLocalAddress() || dns.isMulticastAddress()) {
						continue;
					}
					try {
						builder.addDnsServer(dns);
						added = true;
						count++;
					} catch (Exception ignored) {
						/* dirección no soportada */
					}
				}
			}
		} catch (Exception e) {
			Diag.w(TAG, "No se pudieron leer los DNS de la red activa", e);
		}

		if (!added) {
			try {
				builder.addDnsServer("1.1.1.1");
				builder.addDnsServer("8.8.8.8");
			} catch (Exception ignored) {
				/* nada */
			}
		}
	}

	@SuppressLint("WakelockTimeout")
	private void acquireWakeLock() {
		if (wakeLock != null && wakeLock.isHeld()) {
			return;
		}
		try {
			PowerManager pm = getSystemService(PowerManager.class);
			if (pm == null) {
				return;
			}
			wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
					"NetworkLimiter::vpn");
			wakeLock.setReferenceCounted(false);
			wakeLock.acquire();
		} catch (Exception e) {
			Diag.w(TAG, "No se pudo adquirir el wake lock", e);
		}
	}

	private void releaseWakeLock() {
		if (wakeLock != null) {
			try {
				if (wakeLock.isHeld()) {
					wakeLock.release();
				}
			} catch (Exception ignored) {
				/* nada */
			}
			wakeLock = null;
		}
	}
}
