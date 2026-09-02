package com.kooo.evcam.share;

import android.content.Context;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

/**
 * 「发送到手机」这条链路的实测情况，写进诊断报告。
 *
 * <h3>为什么要单独一节</h3>
 *
 * <p>扫码打不开时，问题可能在三个地方：<b>网卡上根本没有可用地址</b>、
 * <b>地址挑错了</b>、或者<b>端口起不来</b>。这三件事在车上都看不出来，
 * 从截图更看不出来 —— 只能让报告把它们分开写清楚。</p>
 *
 * <p>报告是随时导出的，不要求分享服务正在运行，所以这里不去碰
 * {@link FileShareServer} 的实例，只做两件不干扰任何东西的事：
 * 列地址、试着占一个端口再立刻放掉。</p>
 */
public final class ShareDiagnostics {

    private ShareDiagnostics() {
    }

    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("## 发送到手机（局域网）").append('\n');

        List<LocalNetwork.Endpoint> endpoints = LocalNetwork.enumerate();
        if (endpoints.isEmpty()) {
            sb.append("可用 IPv4 地址: 无").append('\n');
            sb.append("说明: 这时扫码一定打不开 —— 两台设备还没有连到同一个局域网里。")
                    .append('\n');
        } else {
            sb.append("可用 IPv4 地址（按优先级，排第一的是默认选用的）:").append('\n');
            for (int i = 0; i < endpoints.size(); i++) {
                LocalNetwork.Endpoint endpoint = endpoints.get(i);
                sb.append(i == 0 ? "  * " : "    ")
                        .append(endpoint.address)
                        .append("  网卡=").append(endpoint.interfaceName)
                        .append("  判定=").append(endpoint.kind)
                        .append('\n');
            }
            sb.append("说明: 车机开热点时能用的是 HOTSPOT 那一条，")
                    .append("车机连手机热点时能用的是 WIFI 那一条。").append('\n');
        }

        sb.append("端口可用性: ").append(probePort()).append('\n');
        sb.append('\n');
    }

    /**
     * 试着让系统分配一个端口再立刻放掉。
     *
     * <p>回答的是「这台车机允许应用监听端口吗」。有些车机的防护策略会拦下来，
     * 那时候地址再对也没用，而现象同样是「扫了码打不开」。</p>
     */
    private static String probePort() {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket(0);
            return "可以监听（试分配到 " + socket.getLocalPort() + "，已释放）";
        } catch (IOException e) {
            return "无法监听: " + e.getMessage();
        } catch (SecurityException e) {
            return "被安全策略拒绝: " + e.getMessage();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 关不掉也没什么可做的，报告本身已经拿到结论
                }
            }
        }
    }
}
