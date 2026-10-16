package com.kooo.evcam.zeekr;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.settings.SettingsTestHostActivity;
import org.junit.Test;
import static org.junit.Assert.*;
public class PreviewFreshnessAndroidTest {
 static class TimestampSource extends SurfaceTexture {
  long value; TimestampSource(){super(false);}
  @Override public long getTimestamp(){return value;}
 }
 private static void pixel(LiveCameraPreview preview,int color){
  Bitmap bitmap=Bitmap.createBitmap(preview.getWidth(),preview.getHeight(),Bitmap.Config.ARGB_8888);
  preview.draw(new Canvas(bitmap));assertEquals(color,bitmap.getPixel(5,5));bitmap.recycle();
 }
 @Test public void stalledPictureIsOpaqueAndNewSourceMustProgress() {
  Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),SettingsTestHostActivity.class);
  LiveCameraPreview[] holder=new LiveCameraPreview[1]; TimestampSource a=new TimestampSource(),b=new TimestampSource();
  try(ActivityScenario<SettingsTestHostActivity> scenario=ActivityScenario.launch(intent)){
   scenario.onActivity(activity->{
    LiveCameraPreview preview=new LiveCameraPreview(activity);View red=new View(activity);red.setBackgroundColor(Color.RED);
    preview.addView(red,new android.widget.FrameLayout.LayoutParams(-1,-1));activity.setContentView(preview);holder[0]=preview;
   });
   InstrumentationRegistry.getInstrumentation().waitForIdleSync();
   scenario.onActivity(activity->{LiveCameraPreview p=holder[0];pixel(p,Color.BLACK);
    a.value=100;p.noteFrame(a);pixel(p,Color.BLACK);a.value=200;p.noteFrame(a);pixel(p,Color.RED);});
   SystemClock.sleep(600);
   scenario.onActivity(activity->{LiveCameraPreview p=holder[0];pixel(p,Color.BLACK);
    b.value=300;p.noteFrame(b);pixel(p,Color.BLACK);b.value=400;p.noteFrame(b);pixel(p,Color.RED);
    p.resetFrames();pixel(p,Color.BLACK);});
  }finally{a.release();b.release();}
 }
}
