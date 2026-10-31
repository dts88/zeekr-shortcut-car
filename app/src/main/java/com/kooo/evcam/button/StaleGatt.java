package com.kooo.evcam.button;

import android.bluetooth.BluetoothAdapter;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 蓝牙重启过以后，容器手里那份 GATT 服务还是死的：换成系统现在这份（2026-11-01 车主的诊断报告）。
 *
 * <p>车机下电（点火到 ACC）时蓝牙跟着关，旧的 GATT 服务就死了；再开时系统手里是新的。我们的进程跨过这一段活着，而应用跑在容器（VLite）里，
 * {@code getBluetoothGatt} 经过它的一层包装，包装里留着旧的那份，以后每次都还给我们它：蓝牙明明开着，扫描、连接都是 {@code DeadObjectException}
 * （错误 3，紧接着 2）。重开扫描、等多久都没用；重启应用（新进程）马上就好，车主 11-01 试过。</p>
 *
 * <p>做法：不经过包装，现从系统拿一份，把包装里死了的那份换掉。以后的调用照旧经过这层包装，它替我们做的事（身份、权限）一样不少。
 * 只改本进程内存里容器的这一个对象，只在它死了的时候改，不写任何东西。容器的类名是混淆的，所以不认名字，只按「类型和 GATT 服务一样、而且死了」找；
 * 找不到就说找不到（长什么样进黑匣子），和原来一样等重启应用。</p>
 */
final class StaleGatt {

    /** 从包装往里最多走几层。 */
    private static final int MAX_DEPTH = 4;
    /** 最多看多少个对象。 */
    private static final int MAX_OBJECTS = 200;
    /** 找不到时往里记几层。 */
    private static final int DESCRIBE_DEPTH = 2;

    private StaleGatt() {
    }

    static final class Result {
        /** 换了：值得马上再试。 */
        final boolean replaced;
        /** 一句话，进黑匣子。 */
        final String text;

        Result(boolean replaced, String text) {
            this.replaced = replaced;
            this.text = text;
        }
    }

    /** 手里那份活着就什么都不动。走隐藏接口，不让看就说不让看，不抛。 */
    static Result replaceIfDead(BluetoothAdapter adapter) {
        try {
            Field field = BluetoothAdapter.class.getDeclaredField("mManagerService");
            field.setAccessible(true);
            Object manager = field.get(adapter);
            Method getGatt = Class.forName("android.bluetooth.IBluetoothManager").getMethod("getBluetoothGatt");
            Object held = getGatt.invoke(manager);
            if (held == null) {
                return new Result(false, "none");
            }
            if (alive(held)) {
                return new Result(false, "alive");
            }
            if (!Proxy.isProxyClass(held.getClass())) {
                return new Result(false, "dead, not wrapped (" + held.getClass().getName() + ")");
            }
            // 系统自己的代理直接包在 binder 上，不经过容器那一层
            Constructor<?> direct = Class.forName("android.bluetooth.IBluetoothManager$Stub$Proxy")
                    .getDeclaredConstructor(IBinder.class);
            direct.setAccessible(true);
            Object fresh = getGatt.invoke(direct.newInstance(((IInterface) manager).asBinder()));
            if (fresh == null) {
                return new Result(false, "dead; the system has none right now");
            }
            if (Proxy.isProxyClass(fresh.getClass())) {
                return new Result(false, "dead; the system's comes wrapped too");
            }
            if (!alive(fresh)) {
                return new Result(false, "dead; the system's is dead too");
            }
            InvocationHandler wrapper = Proxy.getInvocationHandler(held);
            IBinder freshBinder = ((IInterface) fresh).asBinder();
            int targets = swap(wrapper, value -> value.getClass() == fresh.getClass() && !alive(value), fresh);
            int binders = swap(wrapper,
                    value -> value.getClass() == freshBinder.getClass() && !((IBinder) value).pingBinder(), freshBinder);
            if (targets + binders == 0) {
                return new Result(false, "dead; nothing to replace in " + describe(wrapper, DESCRIBE_DEPTH));
            }
            return new Result(true, "dead; replaced " + targets + " service(s), " + binders + " binder(s); now "
                    + (alive(getGatt.invoke(manager)) ? "alive" : "still dead"));
        } catch (Throwable e) {
            return new Result(false, "can't look (" + e + ")");
        }
    }

    private static boolean alive(Object service) {
        return ((IInterface) service).asBinder().pingBinder();
    }

    /** 从 {@code root} 往里找字段，{@code stale} 认的换成 {@code fresh}，返回换了几处。只进和 {@code root} 同一家（包名前两段一样）的对象。 */
    static int swap(Object root, Predicate<Object> stale, Object fresh) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return walk(root, family(root.getClass()), stale, fresh, seen, MAX_DEPTH);
    }

    private static int walk(Object node, String family, Predicate<Object> stale, Object fresh, Set<Object> seen, int depth) {
        if (depth == 0 || seen.size() >= MAX_OBJECTS || !seen.add(node)) {
            return 0;
        }
        int swapped = 0;
        for (Class<?> type = node.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(node);
                    if (value == null) {
                        continue;
                    }
                    if (stale.test(value)) {
                        field.set(node, fresh);
                        swapped++;
                    } else if (value.getClass().getName().startsWith(family)) {
                        swapped += walk(value, family, stale, fresh, seen, depth - 1);
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                    // 这个字段碰不了：看下一个
                }
            }
        }
        return swapped;
    }

    /** 包名前两段，带最后的点。 */
    private static String family(Class<?> type) {
        String name = type.getName();
        int first = name.indexOf('.');
        int second = first < 0 ? -1 : name.indexOf('.', first + 1);
        return second < 0 ? name : name.substring(0, second + 1);
    }

    /** 找不到时留下它长什么样：字段名和值的类型。 */
    static String describe(Object node, int depth) {
        StringBuilder out = new StringBuilder(node.getClass().getName()).append('{');
        String family = family(node.getClass());
        for (Class<?> type = node.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                out.append(field.getName()).append('=');
                try {
                    field.setAccessible(true);
                    Object value = field.get(node);
                    if (value == null) {
                        out.append("null");
                    } else if (depth > 1 && value != node && value.getClass().getName().startsWith(family)) {
                        out.append(describe(value, depth - 1));
                    } else {
                        out.append(value.getClass().getName());
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                    out.append('?');
                }
                out.append(' ');
            }
        }
        return out.append('}').toString();
    }
}
