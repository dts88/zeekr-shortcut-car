package com.kooo.evcam.zeekr;
import android.content.Context;
import android.os.Build;
import android.view.*;
import android.widget.ImageButton;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.AppConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
@RunWith(AndroidJUnit4.class)
public class MirrorWindowControlsAndroidTest {
    private Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    @Test public void expansionRestoresExactPlacementWithoutReplacingTextureOrSavingTemporarySize() {
        assumeTrue(Build.HARDWARE.contains("ranchu")||Build.PRODUCT.contains("sdk"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();AppConfig config=new AppConfig(c);
            config.setRearViewPosition(280,260);config.setRearViewSize(500,220,2560,1600);
            RearViewMirrorView mirror=new RearViewMirrorView(c,config);
            try {
                mirror.show();assertTrue(mirror.isShowing());
                WindowManager.LayoutParams p=(WindowManager.LayoutParams)field(mirror,"params");
                int x=p.x,y=p.y,w=p.width,h=p.height;Object texture=mirror.getTextureView();
                Map<String,?> saved=new HashMap<>(c.getSharedPreferences("app_config",0).getAll());
                SideViewControls controls=(SideViewControls)field(mirror,"windowControls");
                ImageButton expand=(ImageButton)field(controls,"expand");
                assertNotNull(expand.getContentDescription());assertTrue(expand.performClick());
                assertTrue(p.width>w||p.height>h);assertSame(texture,mirror.getTextureView());
                assertEquals(saved,c.getSharedPreferences("app_config",0).getAll());
                assertTrue(expand.performClick());assertEquals(x,p.x);assertEquals(y,p.y);
                assertEquals(w,p.width);assertEquals(h,p.height);
                expand.performClick();mirror.hide();mirror.show();
                assertEquals(w,p.width);assertSame(texture,mirror.getTextureView());
            } catch(Exception e){throw new AssertionError(e);} finally {mirror.hide();}
        });
    }
    @Test public void narrowControlsKeepCloseAndHideExpandWithoutInterceptingTheWholeWindow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();int[] closed={0};
            SideViewControls controls=new SideViewControls(c,()->closed[0]++,()->{});controls.setMirrorMode(true);
            int width=controls.barHeight();controls.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(controls.barHeight(),View.MeasureSpec.EXACTLY));controls.layoutIn(width);
            try {
                ImageButton close=(ImageButton)field(controls,"close");ImageButton expand=(ImageButton)field(controls,"expand");
                assertEquals(View.GONE,expand.getVisibility());assertEquals(View.VISIBLE,close.getVisibility());
                assertTrue(close.getWidth()>=48*c.getResources().getDisplayMetrics().density);
                assertTrue(close.getLeft()>=0);assertTrue(close.getRight()<=width);assertFalse(controls.isClickable());
                close.performClick();assertEquals(1,closed[0]);
            } catch(Exception e){throw new AssertionError(e);}
        });
    }
}
