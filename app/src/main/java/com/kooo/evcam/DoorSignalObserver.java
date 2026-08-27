package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * 车门信号观察者（基于吉利L6/L7 CarSignalManager API）
 * Author：AbuCoder
 * Date：2023/07/07 
 * Gitee:https://gitee.com/rahman/EVCam
 * Description：车门信号观察者，用于监听车门状态变化，如门打开或关闭。
 * 
 * 核心方法：
 * - getDoorDrvrSts() - 主驾驶门状态
 * - getDoorPassSts() - 副驾驶门状态  
 * - getDoorLeReSts() - 左后门状态
 * - getDoorRiReSts() - 右后门状态
 * 
 * 返回值：1=打开, 2=关闭
 */
public class DoorSignalObserver {
    
    private static final String TAG = "DoorSignalObserver";
    private static final long POLL_INTERVAL_MS = 500; // 500ms轮询一次
    
    /**
     * 车门信号回调接口
     */
    public interface DoorSignalListener {
        /** 车门状态变化 */
        void onDoorOpen(String side);
        void onDoorClose(String side);
        /** 连接状态变化 */
        void onConnectionStateChanged(boolean connected);
    }
    
    private final Context context;
    private final DoorSignalListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private Object carSignalManager = null;
    private Method getDoorDrvrStsMethod = null;  // 主驾驶门
    private Method getDoorPassStsMethod = null;  // 副驾驶门
    private Method getDoorLeReStsMethod = null;  // 左后门
    private Method getDoorRiReStsMethod = null;  // 右后门
    
    private volatile boolean running = false;
    private volatile boolean connected = false;
    
    // 上一次的车门状态（1=打开, 2=关闭）
    private int lastDoorDrvrSts = 2;
    private int lastDoorPassSts = 2;
    private int lastDoorLeReSts = 2;
    private int lastDoorRiReSts = 2;
    
    // 车门开启标志（用于判断是否需要关闭摄像头）
    private boolean isPassDoorOpen = false;      // 副驾驶门
    private boolean isLeftRearDoorOpen = false;  // 左后门
    private boolean isRightRearDoorOpen = false; // 右后门
    
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            AppLog.d(TAG, "🚪 pollRunnable.run() 执行，running=" + running);
            
            if (!running) {
                AppLog.w(TAG, "🚪 running=false，停止轮询");
                return;
            }
            
            try {
                pollDoorState();
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to poll door state", e);
            } finally {
                if (running) {
                    AppLog.d(TAG, "🚪 调度下次轮询，延迟 " + POLL_INTERVAL_MS + "ms");
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                } else {
                    AppLog.w(TAG, "🚪 running=false，不再调度下次轮询");
                }
            }
        }
    };
    
    public DoorSignalObserver(Context context, DoorSignalListener listener) {
        this.context = context;
        this.listener = listener;
    }
    
    /**
     * 启动监听
     */
    public void start() {
        if (running) {
            AppLog.w(TAG, "🚪 车门监听器已经在运行中，跳过重复启动");
            return;
        }
        running = true;
        
        AppLog.i(TAG, "🚪 ========== DoorSignalObserver.start() 开始执行 ==========");
        
        // 重置状态
        lastDoorDrvrSts = 2;
        lastDoorPassSts = 2;
        lastDoorLeReSts = 2;
        lastDoorRiReSts = 2;
        isPassDoorOpen = false;
        isLeftRearDoorOpen = false;
        isRightRearDoorOpen = false;
        
        AppLog.i(TAG, "🚪 启动初始化线程...");
        new Thread(() -> {
            AppLog.i(TAG, "🚪 初始化线程开始运行");
            boolean success = initCarSignalManager();
            AppLog.i(TAG, "🚪 初始化结果: " + (success ? "成功" : "失败"));
            
            if (listener != null) {
                handler.post(() -> {
                    AppLog.i(TAG, "🚪 通知连接状态变化: " + (success ? "已连接" : "未连接"));
                    listener.onConnectionStateChanged(success);
                });
            }
            
            if (success) {
                AppLog.i(TAG, "🚪 准备启动轮询 Runnable...");
                // 延迟 100ms 启动轮询，避免立即被停止
                handler.postDelayed(() -> {
                    AppLog.i(TAG, "🚪 ✅ 轮询 Runnable 准备执行，running=" + running + ", connected=" + connected);
                    if (running && connected) {
                        AppLog.i(TAG, "🚪 开始第一次轮询");
                        pollRunnable.run();
                    } else {
                        AppLog.e(TAG, "🚪 ❌ running=" + running + ", connected=" + connected + "，轮询未启动");
                    }
                }, 100);
            } else {
                AppLog.e(TAG, "🚪 ❌ 初始化失败，轮询未启动");
            }
        }).start();
        
        AppLog.i(TAG, "🚪 ========== DoorSignalObserver.start() 执行完成 ==========");
    }
    
    /**
     * 停止监听
     */
    public void stop() {
        AppLog.i(TAG, "🚪 ========== DoorSignalObserver.stop() 开始执行 ==========");
        AppLog.i(TAG, "🚪 当前 running=" + running);
        
        running = false;
        connected = false;
        
        // 移除所有待执行的 Runnable
        handler.removeCallbacks(pollRunnable);
        AppLog.i(TAG, "🚪 已移除所有待执行的轮询 Runnable");
        
        carSignalManager = null;
        getDoorDrvrStsMethod = null;
        getDoorPassStsMethod = null;
        getDoorLeReStsMethod = null;
        getDoorRiReStsMethod = null;
        
        AppLog.i(TAG, "🚪 ========== DoorSignalObserver.stop() 执行完成 ==========");
    }
    
    /**
     * 当前是否已连接
     */
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * 一次性连接测试（用于 UI 状态检查）
     */
    public static boolean testConnection(Context context) {
        try {
            // 方法1：尝试 ECARX API
            try {
                Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
                Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
                Object binder = getServiceMethod.invoke(null, "ecarxcar_service");
                
                if (binder != null) {
                    Class<?> stubClass = Class.forName("ecarx.car.IECarXCar$Stub");
                    Method asInterfaceMethod = stubClass.getMethod("asInterface", Class.forName("android.os.IBinder"));
                    Object eCarXCar = asInterfaceMethod.invoke(null, binder);
                    
                    if (eCarXCar != null) {
                        Class<?> eCarXCarClass = Class.forName("ecarx.car.ECarXCar");
                        Class<?> iECarXCarClass = Class.forName("ecarx.car.IECarXCar");
                        Method createCarMethod = eCarXCarClass.getMethod("createCar", Context.class, iECarXCarClass);
                        Object car = createCarMethod.invoke(null, context, eCarXCar);
                        
                        if (car != null) {
                            Method getCarManagerMethod = car.getClass().getMethod("getCarManager", String.class, iECarXCarClass);
                            Object carSignalManager = getCarManagerMethod.invoke(car, "car_signal", eCarXCar);
                            
                            if (carSignalManager != null) {
                                Method method = carSignalManager.getClass().getMethod("getDoorDrvrSts");
                                Object result = method.invoke(carSignalManager);
                                AppLog.d(TAG, "✅ ECARX CarSignalManager 可用，主驾门状态: " + result);
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.d(TAG, "ECARX API 不可用: " + e.getMessage());
            }
            
            // 方法2：尝试 CarSensor API (备用)
            try {
                Class<?> clazz = Class.forName("com.ecarx.xui.adaptapi.car.sensor.CarSensor");
                Method createMethod = clazz.getMethod("create", Context.class);
                Object carSensor = createMethod.invoke(null, context);
                
                if (carSensor != null) {
                    Method method = carSensor.getClass().getMethod("getDoorDrvrSts");
                    Object result = method.invoke(carSensor);
                    AppLog.d(TAG, "✅ CarSensor API 可用，主驾门状态: " + result);
                    return true;
                }
            } catch (Exception e) {
                AppLog.d(TAG, "CarSensor API 不可用: " + e.getMessage());
            }
            
            AppLog.e(TAG, "❌ 所有 Car API 均不可用");
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "DoorSignalObserver test failed: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== Internal ====================
    
    /**
     * 初始化 CarSignalManager
     */
    private boolean initCarSignalManager() {
        try {
            AppLog.d(TAG, "🔍 开始初始化 CarSignalManager (车门监听)...");
            
            // 方法1：尝试通过 ServiceManager 获取 ecarxcar_service
            try {
                Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
                Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
                Object binder = getServiceMethod.invoke(null, "ecarxcar_service");
                
                if (binder != null) {
                    AppLog.d(TAG, "✅ ecarxcar_service Binder获取成功");
                    Class<?> stubClass = Class.forName("ecarx.car.IECarXCar$Stub");
                    Method asInterfaceMethod = stubClass.getMethod("asInterface", Class.forName("android.os.IBinder"));
                    Object eCarXCar = asInterfaceMethod.invoke(null, binder);
                    
                    if (eCarXCar != null) {
                        Class<?> eCarXCarClass = Class.forName("ecarx.car.ECarXCar");
                        Class<?> iECarXCarClass = Class.forName("ecarx.car.IECarXCar");
                        Method createCarMethod = eCarXCarClass.getMethod("createCar", Context.class, iECarXCarClass);
                        Object car = createCarMethod.invoke(null, context, eCarXCar);
                        
                        if (car != null) {
                            Method getCarManagerMethod = car.getClass().getMethod("getCarManager", String.class, iECarXCarClass);
                            carSignalManager = getCarManagerMethod.invoke(car, "car_signal", eCarXCar);
                            
                            if (carSignalManager != null) {
                                AppLog.d(TAG, "✅ ECARX CarSignalManager 初始化成功");
                                // 获取车门状态方法
                                getDoorDrvrStsMethod = carSignalManager.getClass().getMethod("getDoorDrvrSts");
                                getDoorPassStsMethod = carSignalManager.getClass().getMethod("getDoorPassSts");
                                getDoorLeReStsMethod = carSignalManager.getClass().getMethod("getDoorLeReSts");
                                getDoorRiReStsMethod = carSignalManager.getClass().getMethod("getDoorRiReSts");
                                
                                // 测试调用
                                Object testResult = getDoorDrvrStsMethod.invoke(carSignalManager);
                                AppLog.d(TAG, "📊 当前主驾门状态: " + testResult);
                                
                                connected = true;
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.w(TAG, "ECARX API 初始化失败: " + e.getMessage());
            }
            
            // 方法2：尝试 CarSensor API (备用)
            try {
                AppLog.d(TAG, "尝试备用 CarSensor API...");
                Class<?> clazz = Class.forName("com.ecarx.xui.adaptapi.car.sensor.CarSensor");
                Method createMethod = clazz.getMethod("create", Context.class);
                carSignalManager = createMethod.invoke(null, context);
                
                if (carSignalManager != null) {
                    AppLog.d(TAG, "✅ CarSensor 初始化成功(备用API)");
                    // 获取车门状态方法
                    getDoorDrvrStsMethod = carSignalManager.getClass().getMethod("getDoorDrvrSts");
                    getDoorPassStsMethod = carSignalManager.getClass().getMethod("getDoorPassSts");
                    getDoorLeReStsMethod = carSignalManager.getClass().getMethod("getDoorLeReSts");
                    getDoorRiReStsMethod = carSignalManager.getClass().getMethod("getDoorRiReSts");
                    
                    // 测试调用
                    Object testResult = getDoorDrvrStsMethod.invoke(carSignalManager);
                    AppLog.d(TAG, "📊 当前主驾门状态: " + testResult);
                    
                    connected = true;
                    return true;
                }
            } catch (Exception e) {
                AppLog.w(TAG, "CarSensor API 初始化失败: " + e.getMessage());
            }
            
            AppLog.e(TAG, "❌ 所有 Car API 初始化失败");
            return false;
            
        } catch (Exception e) {
            AppLog.e(TAG, "❌ CarSignalManager 初始化异常", e);
            carSignalManager = null;
            connected = false;
            return false;
        }
    }
    
    /**
     * 轮询车门状态（500ms间隔）
     */
    private void pollDoorState() {
        if (carSignalManager == null) {
            AppLog.w(TAG, "🚪 carSignalManager 为 null，跳过轮询");
            return;
        }
        
        try {
            // 获取四个车门状态
            int drvr = Integer.parseInt(getDoorDrvrStsMethod.invoke(carSignalManager).toString());
            int pass = Integer.parseInt(getDoorPassStsMethod.invoke(carSignalManager).toString());
            int leRe = Integer.parseInt(getDoorLeReStsMethod.invoke(carSignalManager).toString());
            int riRe = Integer.parseInt(getDoorRiReStsMethod.invoke(carSignalManager).toString());
            
            // 🔍 每次都输出当前车门状态（用于调试）
            AppLog.d(TAG, String.format("🚪 车门状态 - 主驾:%d 副驾:%d 左后:%d 右后:%d", drvr, pass, leRe, riRe));
            
            // 主驾驶门（不触发摄像头，只记录状态）
            if (drvr != lastDoorDrvrSts) {
                AppLog.i(TAG, "🚪 主驾门状态变化: " + lastDoorDrvrSts + " → " + drvr);
                lastDoorDrvrSts = drvr;
            }
            
            // 副驾驶门（右侧摄像头）
            checkDoorChange("副驾门", pass, lastDoorPassSts, (opened) -> {
                isPassDoorOpen = opened;
                if (opened) {
                    notifyDoorOpen("right");
                } else {
                    // 只有当副驾门和右后门都关闭时才关闭右侧摄像头
                    if (!isRightRearDoorOpen) {
                        notifyDoorClose("right");
                    }
                }
            });
            lastDoorPassSts = pass;
            
            // 左后门（左侧摄像头）
            checkDoorChange("左后门", leRe, lastDoorLeReSts, (opened) -> {
                isLeftRearDoorOpen = opened;
                if (opened) {
                    notifyDoorOpen("left");
                } else {
                    // 左后门关闭就可以关闭左侧摄像头
                    notifyDoorClose("left");
                }
            });
            lastDoorLeReSts = leRe;
            
            // 右后门（右侧摄像头）
            checkDoorChange("右后门", riRe, lastDoorRiReSts, (opened) -> {
                isRightRearDoorOpen = opened;
                if (opened) {
                    notifyDoorOpen("right");
                } else {
                    // 只有当副驾门和右后门都关闭时才关闭右侧摄像头
                    if (!isPassDoorOpen) {
                        notifyDoorClose("right");
                    }
                }
            });
            lastDoorRiReSts = riRe;
            
        } catch (Exception e) {
            AppLog.e(TAG, "❌ 车门状态读取失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查车门状态变化
     */
    private void checkDoorChange(String doorName, int currentState, int lastState, DoorChangeCallback callback) {
        if (currentState != lastState) {
            String stateDesc = (currentState == 1) ? "打开" : "关闭";
            AppLog.i(TAG, "🚪 " + doorName + "状态变化: " + lastState + " → " + currentState + " (" + stateDesc + ")");
            
            if (currentState == 1 && lastState != 1) {
                // 车门打开
                AppLog.i(TAG, "🚪🚪🚪 触发车门打开回调: " + doorName);
                callback.onChange(true);
            } else if (currentState == 2 && lastState == 1) {
                // 车门关闭
                AppLog.i(TAG, "🚪🚪🚪 触发车门关闭回调: " + doorName);
                callback.onChange(false);
            }
        }
    }
    
    /**
     * 通知车门打开
     */
    private void notifyDoorOpen(String side) {
        if (listener != null) {
            handler.post(() -> listener.onDoorOpen(side));
        }
    }
    
    /**
     * 通知车门关闭
     */
    private void notifyDoorClose(String side) {
        if (listener != null) {
            handler.post(() -> listener.onDoorClose(side));
        }
    }
    
    /**
     * 车门变化回调接口
     */
    private interface DoorChangeCallback {
        void onChange(boolean opened);
    }
}
