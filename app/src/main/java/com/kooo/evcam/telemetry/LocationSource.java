package com.kooo.evcam.telemetry;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppLog;

/**
 * 定位这一路：经纬度，以及（车辆属性给不出车速时）GPS 推算的车速。
 *
 * <p>要 {@code ACCESS_FINE_LOCATION}。应用不弹权限框（项目所有者 2026-10-06：能用经纬度就直接用）：
 * 已经授予了（比如车机预先授予）才订阅；没有就只记一句「没有定位权限」，经纬度和 GPS 车速不显示，
 * 其余信号照常。容器里定位到底给不给、给的是不是车机的 GPS，没有实测过 ——
 * 这一路本身就是试验的一部分，结果看黑匣子。</p>
 */
final class LocationSource implements LocationListener {

    private static final String TAG = "LocationSource";
    /** 多久要一次；信息条 5 次/秒重画，定位半秒一次足够。 */
    private static final long MIN_INTERVAL_MS = 500L;

    private final Telemetry telemetry;
    private LocationManager manager;
    private HandlerThread thread;
    /** 订阅经过的细节：只进日志和黑匣子（{@link Telemetry#locationReported}）。 */
    private volatile String status = "not started";
    /** 订阅的结果：页面上的状态行只显示这个。 */
    private volatile Telemetry.LocationLink link = Telemetry.LocationLink.OFF;

    LocationSource(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    void start(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            status = "no permission";
            link = Telemetry.LocationLink.NO_PERMISSION;
            telemetry.locationReported(this, status);
            return;
        }
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            status = "no service";
            link = Telemetry.LocationLink.OFF;
            telemetry.locationReported(this, status);
            return;
        }
        thread = new HandlerThread("Telemetry-Location");
        thread.start();
        StringBuilder subscribed = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        boolean denied = false;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) {
                    failed.append(provider).append("=off ");
                    continue;
                }
                manager.requestLocationUpdates(provider, MIN_INTERVAL_MS, 0f, this, thread.getLooper());
                subscribed.append(provider).append(' ');
                Location last = manager.getLastKnownLocation(provider);
                if (last != null) {
                    onLocationChanged(last);
                }
            } catch (Exception e) {
                // SecurityException（权限）、IllegalArgumentException（没有这个提供者）都算连不上
                denied |= e instanceof SecurityException;
                failed.append(provider).append('=').append(e.getClass().getSimpleName()).append(' ');
                AppLog.w(TAG, "订阅 " + provider + " 失败: " + e);
            }
        }
        status = subscribed.length() > 0 ? "subscribed " + subscribed.toString().trim()
                : "no provider";
        if (failed.length() > 0) {
            status += " (" + failed.toString().trim() + ")";
        }
        link = subscribed.length() > 0 ? Telemetry.LocationLink.ON
                : denied ? Telemetry.LocationLink.NO_PERMISSION : Telemetry.LocationLink.OFF;
        telemetry.locationReported(this, status);
    }

    void stop() {
        LocationManager m = manager;
        manager = null;
        if (m != null) {
            try {
                m.removeUpdates(this);
            } catch (Exception e) {
                AppLog.w(TAG, "取消定位订阅失败: " + e);
            }
        }
        HandlerThread t = thread;
        thread = null;
        if (t != null) {
            t.quitSafely();
        }
        status = "stopped";
    }

    Telemetry.LocationLink link() {
        return link;
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        final double lat = location.getLatitude();
        final double lon = location.getLongitude();
        final boolean hasSpeed = location.hasSpeed() && !telemetry.hasCarSpeed();
        final float kmh = location.getSpeed() * 3.6f;
        // 停了之后定位线程上还排着的那一次也会到这里：Telemetry 只收当前来源的写入，它被丢掉，清掉的位置不会被写回来
        telemetry.edit(this, b -> {
            b.position(lat, lon);
            if (hasSpeed) {
                b.speedKmh(kmh);
            }
        });
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }
}
