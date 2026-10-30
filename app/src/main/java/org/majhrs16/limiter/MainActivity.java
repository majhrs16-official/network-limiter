package org.majhrs16.limiter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import org.majhrs16.limiter.core.Format;
import org.majhrs16.limiter.core.Stats;
import org.majhrs16.limiter.core.VpnConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pantalla principal: estado del túnel, límites de bajada/subida y métricas.
 * El menú lateral abre la página de split tunneling. Todo en Java con
 * AppCompat, sin Compose ni Kotlin.
 */
public class MainActivity extends AppCompatActivity {

	private static final int SEEK_MAX = 1000;
	private static final int PAGE_MAIN = 0;
	private static final int PAGE_SPLIT = 1;

	private DrawerLayout drawer;
	private ViewFlipper pages;
	private TextView topTitle;
	private TextView navMain;
	private TextView navSplit;

	private SeekBar downSeek;
	private SeekBar upSeek;
	private SeekBar rttSeek;
	private TextView downValue;
	private TextView upValue;
	private TextView rttValue;
	private TextView statusText;
	private TextView uptime;
	private TextView downNow;
	private TextView upNow;
	private TextView downTotal;
	private TextView upTotal;
	private TextView connections;
	private TextView drops;
	private View statusDot;
	private Button toggle;

	private RadioGroup splitModeGroup;
	private RadioButton splitAll;
	private RadioButton splitInclude;
	private RadioButton splitExclude;
	private EditText splitSearch;
	private CheckBox splitSystem;
	private TextView splitCount;
	private TextView splitNote;
	private ListView splitList;
	private AppListAdapter splitAdapter;
	private boolean appsLoaded;

	private VpnConfig config;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private boolean ignoreSeekEvents;
	private boolean lastRunning;
	private boolean firstRefresh = true;

	private final ActivityResultLauncher<Intent> vpnLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				if (result.getResultCode() == RESULT_OK) {
					launchService(LimiterVpnService.ACTION_START);
				} else {
					Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show();
				}
			});

	private final ActivityResultLauncher<String> notificationLauncher = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(), granted -> startVpn());

	private final Runnable poller = new Runnable() {
		@Override
		public void run() {
			refresh();
			handler.postDelayed(this, 500);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		drawer = findViewById(R.id.drawer_layout);
		pages = findViewById(R.id.pages);
		topTitle = findViewById(R.id.top_title);
		navMain = findViewById(R.id.nav_main);
		navSplit = findViewById(R.id.nav_split);

		ImageButton menu = findViewById(R.id.menu_button);
		menu.setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
		navMain.setOnClickListener(v -> showPage(PAGE_MAIN));
		navSplit.setOnClickListener(v -> showPage(PAGE_SPLIT));

		downSeek = findViewById(R.id.down_seek);
		upSeek = findViewById(R.id.up_seek);
		rttSeek = findViewById(R.id.rtt_seek);
		downValue = findViewById(R.id.down_value);
		upValue = findViewById(R.id.up_value);
		rttValue = findViewById(R.id.rtt_value);
		statusText = findViewById(R.id.status_text);
		uptime = findViewById(R.id.uptime);
		downNow = findViewById(R.id.down_now);
		upNow = findViewById(R.id.up_now);
		downTotal = findViewById(R.id.down_total);
		upTotal = findViewById(R.id.up_total);
		connections = findViewById(R.id.connections);
		drops = findViewById(R.id.drops);
		statusDot = findViewById(R.id.status_dot);
		toggle = findViewById(R.id.toggle);

		splitModeGroup = findViewById(R.id.split_mode);
		splitAll = findViewById(R.id.split_all);
		splitInclude = findViewById(R.id.split_include);
		splitExclude = findViewById(R.id.split_exclude);
		splitSearch = findViewById(R.id.split_search);
		splitSystem = findViewById(R.id.split_system);
		splitCount = findViewById(R.id.split_count);
		splitNote = findViewById(R.id.split_note);
		splitList = findViewById(R.id.split_list);

		config = VpnConfig.load(this);

		downSeek.setMax(SEEK_MAX);
		upSeek.setMax(SEEK_MAX);
		rttSeek.setMax(VpnConfig.MAX_RTT_MS - VpnConfig.MIN_RTT_MS);
		ignoreSeekEvents = true;
		downSeek.setProgress(kbpsToProgress(config.downKbps));
		upSeek.setProgress(kbpsToProgress(config.upKbps));
		rttSeek.setProgress(clampRttProgress(config.rttMs));
		ignoreSeekEvents = false;
		updateLabels();

		SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (!ignoreSeekEvents) {
					updateLabels();
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				persistLimits();
			}
		};
		downSeek.setOnSeekBarChangeListener(listener);
		upSeek.setOnSeekBarChangeListener(listener);
		rttSeek.setOnSeekBarChangeListener(listener);

		toggle.setOnClickListener(v -> onToggle());

		findViewById(R.id.preset_slow).setOnClickListener(v -> applyPreset(1_000, 500));
		findViewById(R.id.preset_medium).setOnClickListener(v -> applyPreset(10_000, 2_000));
		findViewById(R.id.preset_fast).setOnClickListener(v -> applyPreset(50_000, 10_000));
		findViewById(R.id.battery).setOnClickListener(v -> openBatterySettings());

		setupSplitTunnel();

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (drawer.isDrawerOpen(GravityCompat.START)) {
					drawer.closeDrawer(GravityCompat.START);
				} else if (pages.getDisplayedChild() != PAGE_MAIN) {
					showPage(PAGE_MAIN);
				} else {
					setEnabled(false);
					finish();
				}
			}
		});

		showPage(PAGE_MAIN);
	}

	private void setupSplitTunnel() {
		splitAll.setChecked(config.splitMode == VpnConfig.SPLIT_ALL);
		splitInclude.setChecked(config.splitMode == VpnConfig.SPLIT_INCLUDE);
		splitExclude.setChecked(config.splitMode == VpnConfig.SPLIT_EXCLUDE);

		splitModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
			if (checkedId == R.id.split_include) {
				config.splitMode = VpnConfig.SPLIT_INCLUDE;
			} else if (checkedId == R.id.split_exclude) {
				config.splitMode = VpnConfig.SPLIT_EXCLUDE;
			} else {
				config.splitMode = VpnConfig.SPLIT_ALL;
			}
			config.save(this);
			updateSplitCount();
		});

		splitSearch.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				if (splitAdapter != null) {
					splitAdapter.setQuery(s.toString());
					updateSplitCount();
				}
			}
		});

		splitSystem.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (splitAdapter != null) {
				splitAdapter.setShowSystem(isChecked);
			}
		});

		splitList.setOnItemClickListener((parent, view, position, id) -> {
			if (splitAdapter == null) {
				return;
			}
			AppEntry entry = splitAdapter.getItem(position);
			if (!config.splitPackages.remove(entry.pkg)) {
				config.splitPackages.add(entry.pkg);
			}
			splitAdapter.notifyDataSetChanged();
			config.save(this);
			updateSplitCount();
		});

		findViewById(R.id.split_select_all).setOnClickListener(v -> {
			if (splitAdapter != null) {
				splitAdapter.setVisibleSelected(true);
				config.save(this);
				updateSplitCount();
			}
		});

		findViewById(R.id.split_select_none).setOnClickListener(v -> {
			if (splitAdapter != null) {
				splitAdapter.setVisibleSelected(false);
				config.save(this);
				updateSplitCount();
			}
		});

		findViewById(R.id.split_apply).setOnClickListener(v -> applySplitTunnel());

		updateSplitCount();
	}

	@Override
	protected void onResume() {
		super.onResume();
		handler.post(poller);
	}

	@Override
	protected void onPause() {
		handler.removeCallbacks(poller);
		super.onPause();
	}

	private void showPage(int page) {
		pages.setDisplayedChild(page);
		topTitle.setText(page == PAGE_SPLIT ? R.string.split_title : R.string.app_name);
		int accent = ContextCompat.getColor(this, R.color.accent);
		int text = ContextCompat.getColor(this, R.color.text);
		navMain.setTextColor(page == PAGE_MAIN ? accent : text);
		navSplit.setTextColor(page == PAGE_SPLIT ? accent : text);
		drawer.closeDrawer(GravityCompat.START);
		if (page == PAGE_SPLIT && !appsLoaded) {
			loadApps();
		}
	}

	private void loadApps() {
		appsLoaded = true;
		PackageManager pm = getPackageManager();
		List<ApplicationInfo> installed;
		try {
			installed = pm.getInstalledApplications(0);
		} catch (Exception e) {
			installed = new ArrayList<>();
		}

		String self = getPackageName();
		List<AppEntry> entries = new ArrayList<>();
		for (ApplicationInfo info : installed) {
			if (self.equals(info.packageName)) {
				continue;
			}
			boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
					&& (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
			try {
				CharSequence label = pm.getApplicationLabel(info);
				Drawable icon = pm.getApplicationIcon(info);
				entries.add(new AppEntry(info.packageName,
						label == null ? info.packageName : label.toString(), icon, system));
			} catch (Exception ignored) {
				/* app sin recursos accesibles */
			}
		}
		entries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));

		splitAdapter = new AppListAdapter(this, entries, config.splitPackages);
		splitAdapter.setShowSystem(splitSystem.isChecked());
		splitAdapter.setQuery(splitSearch.getText().toString());
		splitList.setAdapter(splitAdapter);
		updateSplitCount();
	}

	private void updateSplitCount() {
		if (splitCount == null) {
			return;
		}
		splitCount.setText(getResources().getQuantityString(R.plurals.split_count,
				config.splitPackages.size(), config.splitPackages.size()));
		if (config.splitMode == VpnConfig.SPLIT_INCLUDE && config.splitPackages.isEmpty()) {
			splitNote.setText(R.string.split_include_empty);
		} else {
			splitNote.setText(R.string.split_note);
		}
	}

	private void applySplitTunnel() {
		config.save(this);
		if (LimiterVpnService.isRunning()) {
			launchService(LimiterVpnService.ACTION_RESTART);
			Toast.makeText(this, R.string.split_saved_restart, Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, R.string.split_saved, Toast.LENGTH_SHORT).show();
		}
	}

	private void onToggle() {
		if (LimiterVpnService.isRunning()) {
			launchService(LimiterVpnService.ACTION_STOP);
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
				!= PackageManager.PERMISSION_GRANTED) {
			notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
		} else {
			startVpn();
		}
	}

	private void startVpn() {
		Intent prepare = VpnService.prepare(this);
		if (prepare != null) {
			vpnLauncher.launch(prepare);
		} else {
			launchService(LimiterVpnService.ACTION_START);
		}
	}

	private void launchService(String action) {
		Intent intent = new Intent(this, LimiterVpnService.class).setAction(action);
		if (LimiterVpnService.ACTION_START.equals(action)) {
			ContextCompat.startForegroundService(this, intent);
		} else {
			startService(intent);
		}
	}

	private void persistLimits() {
		config.downKbps = progressToKbps(downSeek.getProgress());
		config.upKbps = progressToKbps(upSeek.getProgress());
		config.rttMs = VpnConfig.MIN_RTT_MS + rttSeek.getProgress();
		config.save(this);
		if (LimiterVpnService.isRunning()) {
			launchService(LimiterVpnService.ACTION_UPDATE);
		}
	}

	private void applyPreset(long downKbps, long upKbps) {
		ignoreSeekEvents = true;
		downSeek.setProgress(kbpsToProgress(downKbps));
		upSeek.setProgress(kbpsToProgress(upKbps));
		ignoreSeekEvents = false;
		updateLabels();
		config.downKbps = VpnConfig.clampKbps(downKbps);
		config.upKbps = VpnConfig.clampKbps(upKbps);
		config.rttMs = VpnConfig.MIN_RTT_MS + rttSeek.getProgress();
		config.save(this);
		if (LimiterVpnService.isRunning()) {
			launchService(LimiterVpnService.ACTION_UPDATE);
		}
	}

	private void updateLabels() {
		downValue.setText(Format.kbps(progressToKbps(downSeek.getProgress())));
		upValue.setText(Format.kbps(progressToKbps(upSeek.getProgress())));
		rttValue.setText(getString(R.string.rtt_value,
				VpnConfig.MIN_RTT_MS + rttSeek.getProgress()));
	}

	private void refresh() {
		boolean running = LimiterVpnService.isRunning();
		if (firstRefresh || running != lastRunning) {
			statusText.setText(running ? R.string.status_on : R.string.status_off);
			statusDot.setBackgroundResource(running ? R.drawable.dot_on : R.drawable.dot_off);
			toggle.setText(running ? R.string.action_stop : R.string.action_start);
			toggle.setBackgroundResource(running
					? R.drawable.button_danger
					: R.drawable.button_primary);
			lastRunning = running;
			firstRefresh = false;
		}

		Stats.Snapshot snapshot = LimiterVpnService.stats().snapshot();
		uptime.setText(running ? Format.duration(snapshot.uptimeMillis)
				: getString(R.string.zero_time));
		downNow.setText(Format.rate(running ? snapshot.downRate : 0));
		upNow.setText(Format.rate(running ? snapshot.upRate : 0));
		downTotal.setText(Format.bytes(snapshot.downBytes));
		upTotal.setText(Format.bytes(snapshot.upBytes));
		connections.setText(getString(R.string.connections_value,
				snapshot.tcpConnections, snapshot.udpFlows));
		long dropsCount = snapshot.aqmDrops;
		drops.setText(getResources().getQuantityString(R.plurals.drops_value,
				(int) Math.min(Integer.MAX_VALUE, dropsCount), dropsCount));
	}

	@SuppressLint("BatteryLife")
	private void openBatterySettings() {
		try {
			Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
					Uri.parse("package:" + getPackageName()));
			startActivity(intent);
		} catch (Exception e) {
			try {
				startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
			} catch (Exception ignored) {
				Toast.makeText(this, R.string.battery_unavailable, Toast.LENGTH_LONG).show();
			}
		}
	}

	private static int clampRttProgress(int rttMs) {
		int value = Math.max(VpnConfig.MIN_RTT_MS, Math.min(VpnConfig.MAX_RTT_MS, rttMs));
		return value - VpnConfig.MIN_RTT_MS;
	}

	/* Escala logarítmica 64 kbps .. 100 Mbps. */

	private static long progressToKbps(int progress) {
		double min = Math.log(VpnConfig.MIN_KBPS);
		double max = Math.log(VpnConfig.MAX_KBPS);
		double value = Math.exp(min + (max - min) * progress / (double) SEEK_MAX);
		return VpnConfig.clampKbps(Math.round(value));
	}

	private static int kbpsToProgress(long kbps) {
		double min = Math.log(VpnConfig.MIN_KBPS);
		double max = Math.log(VpnConfig.MAX_KBPS);
		double clamped = Math.max(VpnConfig.MIN_KBPS, Math.min(VpnConfig.MAX_KBPS, kbps));
		return (int) Math.round((Math.log(clamped) - min) / (max - min) * SEEK_MAX);
	}

	/* ------------------------------ lista de apps ----------------------- */

	private static final class AppEntry {
		final String pkg;
		final String label;
		final Drawable icon;
		final boolean system;

		AppEntry(String pkg, String label, Drawable icon, boolean system) {
			this.pkg = pkg;
			this.label = label;
			this.icon = icon;
			this.system = system;
		}
	}

	private static final class AppListAdapter extends BaseAdapter {
		private final LayoutInflater inflater;
		private final List<AppEntry> all;
		private final List<AppEntry> visible = new ArrayList<>();
		private final Set<String> selected;
		private boolean showSystem;
		private String query = "";

		AppListAdapter(Context context, List<AppEntry> all, Set<String> selected) {
			this.inflater = LayoutInflater.from(context);
			this.all = all;
			this.selected = selected;
			rebuild();
		}

		void setShowSystem(boolean value) {
			if (showSystem == value) {
				return;
			}
			showSystem = value;
			rebuild();
		}

		void setQuery(String value) {
			String next = value == null ? "" : value.trim();
			if (next.equals(query)) {
				return;
			}
			query = next;
			rebuild();
		}

		void setVisibleSelected(boolean value) {
			for (AppEntry entry : visible) {
				if (value) {
					selected.add(entry.pkg);
				} else {
					selected.remove(entry.pkg);
				}
			}
			notifyDataSetChanged();
		}

		private void rebuild() {
			visible.clear();
			String q = query.toLowerCase(Locale.getDefault());
			for (AppEntry entry : all) {
				if (!showSystem && entry.system) {
					continue;
				}
				if (!q.isEmpty()) {
					String label = entry.label.toLowerCase(Locale.getDefault());
					String pkg = entry.pkg.toLowerCase(Locale.getDefault());
					if (!label.contains(q) && !pkg.contains(q)) {
						continue;
					}
				}
				visible.add(entry);
			}
			notifyDataSetChanged();
		}

		@Override
		public int getCount() {
			return visible.size();
		}

		@Override
		public AppEntry getItem(int position) {
			return visible.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = convertView;
			if (view == null) {
				view = inflater.inflate(R.layout.item_app, parent, false);
			}
			AppEntry entry = getItem(position);
			ImageView icon = view.findViewById(R.id.app_icon);
			TextView label = view.findViewById(R.id.app_label);
			TextView pkg = view.findViewById(R.id.app_package);
			CheckBox check = view.findViewById(R.id.app_check);
			icon.setImageDrawable(entry.icon);
			label.setText(entry.label);
			pkg.setText(entry.pkg);
			check.setChecked(selected.contains(entry.pkg));
			return view;
		}
	}
}
