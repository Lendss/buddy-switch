package app.buddy.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Turns a screenshot into a JPEG away from Buddy's own process. The screenshot is a GPU buffer and copying it goes
 * through the GPU; the Tegra driver then keeps ~20 MB for as long as the process draws anything, and the bubble always
 * does. So the copy runs in the ":shot" process (ShotService), kept while a chat turn may take more screenshots and
 * ended when the turn is over, or after IDLE_MS with no chat running (calls from outside Buddy's chats). If ":shot"
 * can't be had, the copy is done here as before, so the tool still answers.
 */
final class Shot {
    static final int COPY = IBinder.FIRST_CALL_TRANSACTION;
    private static final long IDLE_MS = 60000;
    private static final long BIND_MS = 5000;

    static final class Result {
        final byte[] jpg;
        final int w, h, fullW, fullH;

        Result(byte[] jpg, int w, int h, int fullW, int fullH) {
            this.jpg = jpg;
            this.w = w;
            this.h = h;
            this.fullW = fullW;
            this.fullH = fullH;
        }
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Object lock = new Object();
    private static final Runnable idle = Shot::idleCheck;
    private static Context app;
    private static ServiceConnection conn;
    private static IBinder binder;
    private static int busy; // copies in progress: ":shot" is never let go under one

    /** The screenshot as a JPEG whose short side is at most maxShort. Not on the main thread (waits for ":shot"). */
    static Result jpeg(Context c, HardwareBuffer hb, ColorSpace cs, int maxShort) throws InterruptedException {
        synchronized (lock) {
            busy++;
            main.removeCallbacks(idle);
        }
        try {
            for (int attempt = 0; attempt < 2; attempt++) { // the process may have just died: start it once more
                IBinder b = bind(c);
                if (b == null) break;
                try {
                    Result r = call(b, hb, cs, maxShort);
                    if (r != null) return r;
                    break;
                } catch (RemoteException e) {
                    synchronized (lock) {
                        if (binder == b) binder = null;
                    }
                }
            }
            return encode(hb, cs, maxShort);
        } finally {
            synchronized (lock) {
                if (--busy == 0) main.postDelayed(idle, IDLE_MS);
            }
        }
    }

    /** A chat turn is over: let ":shot" go now (its process ends with the service), unless a copy is under way. */
    static void release() {
        synchronized (lock) {
            if (busy > 0 || conn == null) return;
            main.removeCallbacks(idle);
            unbind();
        }
    }

    private static void idleCheck() {
        if (ChatHub.anyRunning()) main.postDelayed(idle, IDLE_MS); // a long turn: kept until it ends
        else release();
    }

    private static IBinder bind(Context c) throws InterruptedException {
        final CountDownLatch up = new CountDownLatch(1);
        synchronized (lock) {
            if (binder != null && binder.isBinderAlive()) return binder;
            app = c.getApplicationContext();
            unbind();
            ServiceConnection sc = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    synchronized (lock) {
                        if (conn == this) binder = b;
                    }
                    up.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    synchronized (lock) {
                        if (conn == this) binder = null;
                    }
                }

                @Override
                public void onBindingDied(ComponentName n) {
                    onServiceDisconnected(n);
                    up.countDown();
                }
            };
            conn = sc;
            if (!app.bindService(new Intent(app, ShotService.class), sc, Context.BIND_AUTO_CREATE)) {
                unbind();
                return null;
            }
        }
        up.await(BIND_MS, TimeUnit.MILLISECONDS);
        synchronized (lock) {
            return binder;
        }
    }

    private static void unbind() {
        if (conn == null) return;
        try {
            app.unbindService(conn);
        } catch (IllegalArgumentException ignored) {
        }
        conn = null;
        binder = null;
    }

    /** Null when ":shot" got the buffer but couldn't make a JPEG of it: then it's tried here. */
    private static Result call(IBinder b, HardwareBuffer hb, ColorSpace cs, int maxShort) throws RemoteException {
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeTypedObject(hb, 0);
            in.writeInt(cs == null ? -1 : cs.getId());
            in.writeInt(maxShort);
            b.transact(COPY, in, out, 0);
            if (out.readInt() == 0) return null;
            return new Result(out.createByteArray(), out.readInt(), out.readInt(), out.readInt(), out.readInt());
        } finally {
            in.recycle();
            out.recycle();
        }
    }

    /** ShotService's side of call(). */
    static void serve(Parcel in, Parcel out) {
        HardwareBuffer hb = in.readTypedObject(HardwareBuffer.CREATOR);
        int cs = in.readInt();
        int maxShort = in.readInt();
        try {
            Result r = encode(hb, colorSpace(cs), maxShort);
            out.writeInt(1);
            out.writeByteArray(r.jpg);
            out.writeInt(r.w);
            out.writeInt(r.h);
            out.writeInt(r.fullW);
            out.writeInt(r.fullH);
        } catch (RuntimeException e) {
            out.writeInt(0);
        } finally {
            if (hb != null) hb.close();
        }
    }

    private static ColorSpace colorSpace(int id) {
        ColorSpace.Named[] named = ColorSpace.Named.values(); // a named space's id is its ordinal
        return ColorSpace.get(id >= 0 && id < named.length ? named[id] : ColorSpace.Named.SRGB);
    }

    static Result encode(HardwareBuffer hb, ColorSpace cs, int maxShort) {
        Bitmap hw = hb == null ? null : Bitmap.wrapHardwareBuffer(hb, cs);
        if (hw == null) throw new IllegalStateException("The screenshot can't be read.");
        Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        if (full == null) throw new IllegalStateException("The screenshot can't be read.");
        try {
            float scale = Math.min(1f, (float) maxShort / Math.min(full.getWidth(), full.getHeight()));
            Bitmap small = scale < 1f
                    ? Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * scale), Math.round(full.getHeight() * scale), true)
                    : full;
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 70, jpg);
            Result r = new Result(jpg.toByteArray(), small.getWidth(), small.getHeight(), full.getWidth(), full.getHeight());
            if (small != full) small.recycle();
            return r;
        } finally {
            full.recycle();
        }
    }
}
