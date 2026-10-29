package com.kooo.evcam.zeekr;
import android.content.Context;
import android.os.*;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.AppConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
@RunWith(AndroidJUnit4.class)
public class SidePopupLayoutAndroidTest {
    private WindowManager.LayoutParams params(SideViewWindow view)throws Exception{
        Field f=SideViewWindow.class.getDeclaredField("params");f.setAccessible(true);return (WindowManager.LayoutParams)f.get(view);
    }
    private void event(SideViewWindow view,int action,int x,int y){
        long now=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(now,now,action,x,y,0);view.onTouchEvent(e);e.recycle();
    }
    private void pinchEvent(SideViewWindow view,long down,long time,int action,float span,boolean two){
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[two?2:1];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[properties.length];
        for(int n=0;n<properties.length;n++){
            properties[n]=new MotionEvent.PointerProperties();properties[n].id=n;properties[n].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[n]=new MotionEvent.PointerCoords();coords[n].x=700+(n==0?-span/2:span/2);coords[n].y=400;coords[n].pressure=1;coords[n].size=1;
        }
        MotionEvent e=MotionEvent.obtain(down,time,action,properties.length,properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        view.onTouchEvent(e);e.recycle();

    }
    @Test public void optInDragPersistsButCancelAndFixedModeDoNotChangeSavedPlacement() {
        assumeTrue(Build.HARDWARE.contains("ranchu")||Build.PRODUCT.contains("sdk"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();AppConfig config=new AppConfig(c);
            config.resetSidePopupLayout();config.setSidePopupAdjustable(false);SideViewWindow view=new SideViewWindow(c,config,()->{});
            try {
                view.show(LaneCycle.RIGHT);WindowManager.LayoutParams p=params(view);int initialX=p.x,initialY=p.y;Object texture=view.getTextureView();
                event(view,MotionEvent.ACTION_DOWN,300,400);event(view,MotionEvent.ACTION_MOVE,200,500);event(view,MotionEvent.ACTION_UP,200,500);
                assertEquals(initialX,p.x);assertEquals(-1,config.getSidePopupX());
                config.setSidePopupAdjustable(true);event(view,MotionEvent.ACTION_DOWN,300,400);
                event(view,MotionEvent.ACTION_MOVE,200,500);event(view,MotionEvent.ACTION_UP,200,500);
                assertEquals(initialX-100,p.x);assertEquals(initialY+100,p.y);assertEquals(p.x,config.getSidePopupX());
                int saved=p.x;event(view,MotionEvent.ACTION_DOWN,300,400);event(view,MotionEvent.ACTION_MOVE,100,400);
                event(view,MotionEvent.ACTION_CANCEL,100,400);assertEquals(saved,p.x);assertEquals(saved,config.getSidePopupX());
                config.setSidePopupAspect("1:1");view.applyLayoutFromConfig();assertEquals(p.width,p.height);assertSame(texture,view.getTextureView());
                int beforePinch=p.height;long down=SystemClock.uptimeMillis();
                pinchEvent(view,down,down,MotionEvent.ACTION_DOWN,200,false);
                pinchEvent(view,down,down+10,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),200,true);
                pinchEvent(view,down,down+40,MotionEvent.ACTION_MOVE,400,true);
                pinchEvent(view,down,down+70,MotionEvent.ACTION_MOVE,600,true);
                pinchEvent(view,down,down+100,MotionEvent.ACTION_MOVE,800,true);
                pinchEvent(view,down,down+130,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),800,true);
                pinchEvent(view,down,down+150,MotionEvent.ACTION_UP,800,false);
                assertTrue("Two-finger resize changes the real window",p.height>beforePinch);
                assertEquals(p.height,Math.round(config.getSidePopupSize()*c.getResources().getDisplayMetrics().heightPixels/100f),16);
                config.resetSidePopupLayout();view.applyLayoutFromConfig();assertTrue(config.isSidePopupAdjustable());
                assertEquals("11:5",config.getSidePopupAspect());assertEquals(33,config.getSidePopupSize());assertEquals(initialX,p.x);
            }catch(Exception e){throw new AssertionError(e);}finally{view.detach();config.resetSidePopupLayout();config.setSidePopupAdjustable(false);}
        });
    }
}
