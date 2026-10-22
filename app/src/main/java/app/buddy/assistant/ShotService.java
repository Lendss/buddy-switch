package app.buddy.assistant;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/** Runs in the ":shot" process and makes screenshots into JPEGs there (see Shot). The process ends with it. */
public final class ShotService extends Service {
    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel in, Parcel out, int flags) throws RemoteException {
            if (code != Shot.COPY) return super.onTransact(code, in, out, flags);
            Shot.serve(in, out);
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Process.killProcess(Process.myPid()); // left cached, it would keep what the GPU driver holds
    }
}
