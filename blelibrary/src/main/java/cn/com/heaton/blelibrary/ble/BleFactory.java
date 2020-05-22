package cn.com.heaton.blelibrary.ble;

import android.bluetooth.BluetoothDevice;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import cn.com.heaton.blelibrary.ble.Ble;
import cn.com.heaton.blelibrary.ble.model.BleDevice;

public final class BleFactory<T extends BleDevice> {

    public static <T extends BleDevice> T create(BluetoothDevice device) {
        return (T) new BleDevice(device);
    }

}
