package com.kooo.evcam.zeekr;
import org.junit.Test;
import static org.junit.Assert.*;
public class SideAutoFitTest {
    @Test public void defaultFitRetainsBoundsButOffKeepsRequestedWidthAndAim() {
        SideViewFraming f = new SideViewFraming(); float[] before = new float[2], after = new float[2];
        f.configure(90, 80, 15, LaneCycle.LEFT, 2.2f);
        assertTrue(f.effectiveFovDegrees() < 90); f.sourcePoint(.5f,.5f,before);
        assertTrue(f.configure(90,80,15,LaneCycle.LEFT,2.2f,false));
        assertEquals(90,f.effectiveFovDegrees(),.001f); f.sourcePoint(.5f,.5f,after);
        assertArrayEquals(before,after,.0001f);
        assertFalse(f.configure(90,80,15,LaneCycle.LEFT,2.2f,false));
        assertTrue(f.configure(90,80,15,LaneCycle.LEFT,2.2f,true));
        assertTrue(f.effectiveFovDegrees() < 90);
    }
    @Test public void unfittedCoordinatesRetainMissingCoverageRatherThanClampIt() {
        for(int lane:new int[]{LaneCycle.LEFT,LaneCycle.RIGHT}) {
            SideViewFraming f=new SideViewFraming(); f.configure(130,80,35,lane,1,false);
            float[] p=new float[2]; boolean outside=false;
            for(int y=0;y<=10;y++)for(int x=0;x<=10;x++) {
                f.sourcePoint(x/10f,y/10f,p);
                outside|=p[0]<0||p[0]>1||p[1]<0||p[1]>1;
            }
            assertTrue(outside);
        }
    }
}
