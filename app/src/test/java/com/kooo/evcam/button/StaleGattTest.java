package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** 在容器的包装里找死了的 GATT 服务（{@link StaleGatt#swap}）：容器的类名是混淆的，只靠字段的值认。 */
public class StaleGattTest {

    private static final class Service {
        final boolean dead;

        Service(boolean dead) {
            this.dead = dead;
        }
    }

    private static final Predicate<Object> DEAD = value -> value instanceof Service && ((Service) value).dead;
    private static final Service FRESH = new Service(false);

    private static class Base {
        private Object inherited;
    }

    private static final class Wrapper extends Base {
        private final Object target;
        private Holder holder;
        private Wrapper self;
        private AtomicReference<Object> elsewhere;
        private int count;

        Wrapper(Object target) {
            this.target = target;
        }
    }

    private static final class Holder {
        private Object service;
        private Holder next;
    }

    @Test
    public void replacesTheDeadServiceEvenInAFinalField() {
        Wrapper wrapper = new Wrapper(new Service(true));
        assertEquals(1, StaleGatt.swap(wrapper, DEAD, FRESH));
        assertSame(FRESH, wrapper.target);
    }

    @Test
    public void replacesItInAnInheritedField() {
        Wrapper wrapper = new Wrapper(null);
        ((Base) wrapper).inherited = new Service(true);
        assertEquals(1, StaleGatt.swap(wrapper, DEAD, FRESH));
        assertSame(FRESH, ((Base) wrapper).inherited);
    }

    @Test
    public void replacesItInsideTheWrappersOwnObjects() {
        Wrapper wrapper = new Wrapper(null);
        wrapper.holder = new Holder();
        wrapper.holder.next = new Holder();
        wrapper.holder.next.service = new Service(true);
        assertEquals(1, StaleGatt.swap(wrapper, DEAD, FRESH));
        assertSame(FRESH, wrapper.holder.next.service);
    }

    @Test
    public void replacesEveryDeadCopy() {
        Wrapper wrapper = new Wrapper(new Service(true));
        wrapper.holder = new Holder();
        wrapper.holder.service = new Service(true);
        assertEquals(2, StaleGatt.swap(wrapper, DEAD, FRESH));
    }

    @Test
    public void leavesALiveServiceAlone() {
        Service live = new Service(false);
        Wrapper wrapper = new Wrapper(live);
        assertEquals(0, StaleGatt.swap(wrapper, DEAD, FRESH));
        assertSame(live, wrapper.target);
    }

    @Test
    public void staysInsideTheWrappersFamily() {
        Service dead = new Service(true);
        Wrapper wrapper = new Wrapper(null);
        wrapper.elsewhere = new AtomicReference<>(dead);
        assertEquals(0, StaleGatt.swap(wrapper, DEAD, FRESH));
        assertSame(dead, wrapper.elsewhere.get());
    }

    @Test
    public void stopsAtTheDepthLimit() {
        Wrapper wrapper = new Wrapper(null);
        Holder holder = new Holder();
        wrapper.holder = holder;
        for (int i = 0; i < 10; i++) {
            holder.next = new Holder();
            holder = holder.next;
        }
        holder.service = new Service(true);
        assertEquals(0, StaleGatt.swap(wrapper, DEAD, FRESH));
    }

    @Test
    public void aLoopEnds() {
        Wrapper wrapper = new Wrapper(new Service(true));
        wrapper.self = wrapper;
        wrapper.holder = new Holder();
        wrapper.holder.next = wrapper.holder;
        assertEquals(1, StaleGatt.swap(wrapper, DEAD, FRESH));
    }

    @Test
    public void describesWhatItCouldNotFind() {
        Wrapper wrapper = new Wrapper(new Service(false));
        wrapper.holder = new Holder();
        String text = StaleGatt.describe(wrapper, 2);
        assertTrue(text, text.contains("target=" + Service.class.getName()));
        assertTrue(text, text.contains("holder=" + Holder.class.getName() + "{"));
        assertTrue(text, text.contains("inherited=null"));
    }
}
