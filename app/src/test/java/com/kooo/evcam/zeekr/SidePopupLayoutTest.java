package com.kooo.evcam.zeekr;
import org.junit.Test;
import static org.junit.Assert.*;
public class SidePopupLayoutTest {
    @Test public void untouchedLayoutExactlyMatchesExistingPlacement() {
        for(int lane:new int[]{LaneCycle.LEFT,LaneCycle.RIGHT}){
            SideViewPlacement.Rect a=SideViewPlacement.compute(2560,1600,lane);
            SideViewPlacement.Rect b=SideViewPlacement.compute(2560,1600,lane,2.2f,33);
            assertEquals(a.left,b.left);assertEquals(a.top,b.top);assertEquals(a.width,b.width);assertEquals(a.height,b.height);
        }
    }
    @Test public void customShapesRetainAspectAndFitBothAxes() {
        for(String shape:new String[]{"1:1","3:4","16:9","11:5"}) for(int percent:new int[]{15,33,70}) {
            float aspect=SideViewPlacement.aspect(shape);
            SideViewPlacement.Rect r=SideViewPlacement.compute(900,500,LaneCycle.RIGHT,aspect,percent);
            assertTrue(r.left>=0&&r.left+r.width<=900);assertTrue(r.top>=0&&r.top+r.height<=500);
            assertEquals(aspect,r.width/(float)r.height,.015f);
        }
    }
    @Test public void invalidInputsStillProduceBoundedGeometry() {
        for(float aspect:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}) {
            SideViewPlacement.Rect r=SideViewPlacement.compute(1,1,LaneCycle.LEFT,aspect,999);
            assertTrue(r.width>0&&r.width<=1);assertTrue(r.height>0&&r.height<=1);
        }
    }
}
