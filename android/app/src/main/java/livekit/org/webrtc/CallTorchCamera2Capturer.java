package livekit.org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;

/** Uses Camera2Capturer's protected factory; all session work stays on its capture thread. */
public final class CallTorchCamera2Capturer extends Camera2Capturer {
  public interface TorchObserver { void onTorchState(boolean available, boolean on); }
  private final TorchObserver observer;
  private Handler cameraHandler;
  private volatile CallTorchCamera2Session torchSession;
  private volatile int orientationMetadata;
  // Packed and published on the capture thread before this session can deliver frames.
  public int getOrientationMetadata() { return orientationMetadata; }

  public CallTorchCamera2Capturer(Context context, String cameraName,
      CameraEventsHandler events, TorchObserver observer) {
    super(context, cameraName, events);
    this.observer = observer;
  }

  @Override public void initialize(SurfaceTextureHelper helper, Context context, CapturerObserver observer) {
    cameraHandler = helper.getHandler();
    super.initialize(helper, context, observer);
  }

  @Override protected void createCameraSession(CameraSession.CreateSessionCallback callback,
      CameraSession.Events events, Context context, SurfaceTextureHelper helper,
      String cameraName, int width, int height, int fps) {
    torchSession = null;
    try {
      android.hardware.camera2.CameraCharacteristics c =
          ((CameraManager) context.getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics(cameraName);
      int sensor = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);
      boolean front = c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
          android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;
      orientationMetadata = sensor | (front ? 512 : 0);
    } catch (android.hardware.camera2.CameraAccessException | RuntimeException e) {
      callback.onFailure(CameraSession.FailureType.ERROR, "Camera orientation unavailable");
      return;
    }
    CallTorchCamera2Session.create(new CameraSession.CreateSessionCallback() {
      @Override public void onDone(CameraSession session) {
        torchSession = (CallTorchCamera2Session) session;
        callback.onDone(session);
      }
      @Override public void onFailure(CameraSession.FailureType type, String error) {
        observer.onTorchState(false, false);
        callback.onFailure(type, error);
      }
    }, events, context, (CameraManager) context.getSystemService(Context.CAMERA_SERVICE),
        helper, cameraName, width, height, fps, observer);
  }

  public void setTorch(boolean on) {
    if (cameraHandler != null) CallTorchControl.enqueue(
        command -> cameraHandler.post(command), () -> torchSession, session -> session.setTorch(on));
  }

  public void toggleTorch() {
    if (cameraHandler != null) CallTorchControl.enqueue(
        command -> cameraHandler.post(command), () -> torchSession, CallTorchCamera2Session::toggleTorch);
  }
}
