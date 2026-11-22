package me.amrbashir.raphael.fingerprint.calibration;

import android.os.*;
import java.util.*;

final class GoodixFactory {
    static final String DAEMON = "vendor.goodix.hardware.biometrics.fingerprint@2.1::IGoodixFingerprintDaemon";
    static final String CALLBACK = "vendor.goodix.hardware.biometrics.fingerprint@2.1::IGoodixFingerprintDaemonCallback";
    static final String BASE = "android.hidl.base@1.0::IBase";

    final IHwBinder remote;
    final FactoryCallback callback = new FactoryCallback();

    GoodixFactory() throws Exception {
        HwBinder.configureRpcThreadpool(2, false);
        remote = HwBinder.getService(DAEMON, "default", false);
        if (remote == null) throw new IllegalStateException("Goodix service unavailable");

        HwParcel q = new HwParcel(), r = new HwParcel();
        try {
            q.writeInterfaceToken(BASE);
            remote.transact(256136003, q, r, 0);
            r.verifySuccess();
            if (!DAEMON.equals(r.readString())) throw new IllegalStateException("Wrong interface");
        } finally {
            release(q, r);
        }
    }

    void attach() throws Exception {
        setNotify(callback);
    }

    void detach() throws Exception {
        setNotify(null);
    }

    private void setNotify(IHwBinder binder) throws RemoteException {
        HwParcel q = new HwParcel(), r = new HwParcel();
        try {
            q.writeInterfaceToken(DAEMON);
            q.writeStrongBinder(binder);
            remote.transact(1, q, r, 0);
            r.verifySuccess();
        } finally {
            release(q, r);
        }
    }

    static void checkConfig(ArrayList<Byte> bytes) {
        if (bytes == null || bytes.isEmpty() || bytes.size() > 65536 || bytes.size() % 8 != 0) throw new IllegalArgumentException("The sensor configuration could not be read.");

        byte[] data = new byte[bytes.size()];
        for (int i = 0; i < data.length; i++)
            data[i] = bytes.get(i);
        java.nio.ByteBuffer input = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Map<Integer, Integer> config = new HashMap<Integer, Integer>();

        while (input.hasRemaining()) {
            int token = input.getInt(), value = input.getInt();
            Integer previous = config.put(token, value);

            // Stock repeats the status field; only conflicting duplicates are invalid.
            if (previous != null && previous.intValue() != value) throw new IllegalArgumentException("The sensor configuration is invalid.");
        }

        if (!Integer.valueOf(0).equals(config.get(100)) || !Integer.valueOf(6).equals(config.get(101)) || !Integer.valueOf(0).equals(config.get(102)) || !Integer.valueOf(192).equals(config.get(6027)) || !Integer.valueOf(224).equals(config.get(6028))) throw new IllegalArgumentException("This fingerprint sensor is not supported.");
    }

    static boolean allowed(int c) {
        switch (c) {
        case 1549:
        case 1556:
        case 1557:
        case 1558:
        case 1559:
        case 1560:
        case 1565:
        case 1566:
        case 1568:
        case 1569:
        case 1570:
        case 1571:
        case 1573:
        case 1686:
        case 1687:
            return true;
        default:
            return false;
        }
    }

    int send(int command, byte[] params) throws Exception {
        if (!allowed(command)) throw new IllegalArgumentException("Command outside fixed calibration sequence");

        HwParcel q = new HwParcel(), r = new HwParcel();
        try {
            q.writeInterfaceToken(DAEMON);
            q.writeInt32(command);

            ArrayList<Byte> bytes = new ArrayList<Byte>();
            for (byte b : params)
                bytes.add(b);
            q.writeInt8Vector(bytes);

            remote.transact(2, q, r, 0);
            r.verifySuccess();

            int status = r.readInt32();
            r.readInt8Vector();
            return status;
        } finally {
            release(q, r);
        }
    }

    private static void release(HwParcel q, HwParcel r) {
        try {
            q.releaseTemporaryStorage();
        } finally {
            try {
                q.release();
            } finally {
                r.release();
            }
        }
    }

    static final class FactoryCallback extends HwBinder implements IHwInterface {
        private boolean armed;
        private int expected;

        volatile ArrayList<Byte> payload;
        volatile String failure;

        synchronized void arm(int command) {
            expected = command;
            payload = null;
            failure = null;
            armed = true;
        }

        public IHwBinder asBinder() {
            return this;
        }

        public IHwInterface queryLocalInterface(String name) {
            return CALLBACK.equals(name) ? this : null;
        }

        public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) {
            return true;
        }

        public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) {
            return true;
        }

        @Override
        public void onTransact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException {
            if (code == 1) {
                if ((flags & 1) == 0) {
                    reply.writeStatus(Integer.MIN_VALUE);
                    reply.send();
                    return;
                }

                request.enforceInterface(CALLBACK);
                request.readInt64();
                int message = request.readInt32();
                int command = request.readInt32();
                ArrayList<Byte> data = request.readInt8Vector();

                synchronized (this) {
                    if (armed && message == 1001 && command == expected) {
                        if (data.size() > 65536)
                            failure = "Oversized factory callback";
                        else
                            payload = new ArrayList<Byte>(data);
                        armed = false;
                    }
                }
                return;
            }

            request.enforceInterface(BASE);
            if (code == 256462420 || code == 257120595) return; // one-way instrumentation/no-op
            if ((flags & 1) != 0) return;

            switch (code) {
            case 256067662:
                reply.writeStatus(0);
                reply.writeStringVector(new ArrayList<String>(Arrays.asList(CALLBACK, BASE)));
                break;

            case 256136003:
                reply.writeStatus(0);
                reply.writeString(CALLBACK);
                break;

            case 256131655: // debug: no output or file descriptors used
            case 256921159: // ping
                reply.writeStatus(0);
                break;

            case 256398152:
                HwBlob header = new HwBlob(16);
                HwBlob hashes = new HwBlob(64);
                header.putInt32(8, 2);
                header.putBool(12, false);
                hashes.putInt8Array(0, hex("40476fa344777ec69649055336981e4cb42a02a954b187bc13f7a8fbeebbb6e7"));
                hashes.putInt8Array(32, hex("bddab6184d7a346da6a07dc0828cf19a696f4caa3611c51f2e14565a14b40fd9"));
                header.putBlob(0, hashes);
                reply.writeStatus(0);
                reply.writeBuffer(header);
                break;

            case 257049926:
                HwBlob debug = new HwBlob(24);
                debug.putInt32(0, -1);
                debug.putInt64(8, 0);
                debug.putInt32(16, 0);
                reply.writeStatus(0);
                reply.writeBuffer(debug);
                break;

            default:
                reply.writeStatus(Integer.MIN_VALUE);
                break;
            }

            reply.send();
        }

        private static byte[] hex(String text) {
            byte[] output = new byte[text.length() / 2];

            for (int i = 0; i < output.length; i++)
                output[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);

            return output;
        }
    }
}
