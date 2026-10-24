package com.kooo.evcam.zeekr;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class UnfittedSideViewAndroidTest {
    @Test public void missingAreasAreOpaqueBlackAndNeverSampleAdjacentLanes() {
        for(int lane:new int[]{LaneCycle.LEFT,LaneCycle.RIGHT}) for(float aspect:new float[]{1f,2.2f}) {
            SideViewFraming framing=new SideViewFraming(); framing.configure(90,60,15,lane,aspect,false);
            FisheyeMesh mesh=new FisheyeMesh(); mesh.prepare(16,100,100,160,160,framing::sourcePoint);
            Bitmap output=Bitmap.createBitmap(400,Math.round(400/aspect),Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(output); Paint p=new Paint();
            mesh.drawClippedToLane(c,0,0,output.getWidth(),output.getHeight(), canvas->{
                canvas.drawColor(Color.MAGENTA);p.setColor(Color.CYAN);canvas.drawRect(100,100,260,260,p);
            });
            int black=0,cyan=0,magenta=0;
            for(int y=0;y<output.getHeight();y++)for(int x=0;x<output.getWidth();x++) {
                int color=output.getPixel(x,y);assertEquals(255,Color.alpha(color));
                if(color==Color.BLACK)black++;if(color==Color.CYAN)cyan++;if(color==Color.MAGENTA)magenta++;
            }
            output.recycle();assertTrue(black>100);assertTrue(cyan>100);assertEquals(0,magenta);
        }
    }
}
