package livekit.org.webrtc;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Capture-thread-owned request state. The OFF request is the immutable request
 * which already started video successfully. Main's delayed observer is output
 * only; it never decides the next toggle. No Android/JNI is needed to test this.
 */
public final class CallTorchControl<R> {
  public interface RequestFactory<R> { R create() throws Exception; }
  public interface RequestSink<R> { void submit(R request) throws Exception; }
  public interface Observer { void changed(boolean available, boolean on); }

  private final R offRequest;
  private final RequestFactory<R> onRequest;
  private final RequestSink<R> sink;
  private final Observer observer;
  private final Consumer<Exception> fatal;
  private final Consumer<Exception> rejected;
  private boolean running = true;
  private boolean available;
  private boolean on;

  public CallTorchControl(boolean available, R offRequest, RequestFactory<R> onRequest,
      RequestSink<R> sink, Observer observer, Consumer<Exception> fatal, Consumer<Exception> rejected) {
    this.available = available;
    this.offRequest = offRequest;
    this.onRequest = onRequest;
    this.sink = sink;
    this.observer = observer;
    this.fatal = fatal;
    this.rejected = rejected;
  }

  public void publish() { observer.changed(available, on); }

  public void toggle() {
    if (running && available) set(!on);
  }

  public void set(boolean enabled) {
    if (!running || (enabled && !available) || enabled == on) return;
    try {
      sink.submit(enabled ? onRequest.create() : offRequest);
      on = enabled;
      publish();
    } catch (IllegalArgumentException requestRejection) {
      // Invalid request parameters are not a camera/device failure. Disable
      // torch for this session and restore the exact known-working video request.
      available = false;
      try {
        sink.submit(offRequest);
        on = false;
      } catch (IllegalArgumentException restoreRejection) {
        // Keep the last accepted light state if even the baseline is rejected.
        // Explicit OFF/teardown may retry; never claim a failed OFF succeeded.
        if (restoreRejection != requestRejection) requestRejection.addSuppressed(restoreRejection);
      } catch (Exception cameraFailure) {
        fatal.accept(cameraFailure);
        return;
      }
      rejected.accept(requestRejection);
      publish();
    } catch (Exception cameraFailure) {
      // Camera2's CameraAccessException/IllegalStateException mean a disconnected,
      // failed or closed camera/session. Preserve its existing fatal-error path.
      fatal.accept(cameraFailure);
    }
  }

  /** Called during camera teardown, which closes the device even if OFF fails. */
  public void stop() {
    if (!running) return;
    running = false;
    if (on) {
      try { sink.submit(offRequest); } catch (Exception ignored) {}
    }
    available = false;
    on = false;
    publish();
  }

  /** Capture the owning session at input time; a queued tap cannot target its replacement. */
  public static <S> void enqueue(Executor executor, Supplier<S> current, Consumer<S> action) {
    S requested = current.get();
    if (requested != null) executor.execute(() -> {
      if (current.get() == requested) action.accept(requested);
    });
  }
}
