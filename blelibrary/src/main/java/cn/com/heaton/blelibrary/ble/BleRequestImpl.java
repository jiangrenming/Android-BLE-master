package cn.com.heaton.blelibrary.ble;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.support.v4.os.HandlerCompat;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cn.com.heaton.blelibrary.BuildConfig;
import cn.com.heaton.blelibrary.ble.callback.wrapper.ConnectWrapperCallback;
import cn.com.heaton.blelibrary.ble.callback.wrapper.MtuWrapperCallback;
import cn.com.heaton.blelibrary.ble.callback.wrapper.NotifyWrapperCallback;
import cn.com.heaton.blelibrary.ble.callback.wrapper.ReadRssiWrapperCallback;
import cn.com.heaton.blelibrary.ble.callback.wrapper.ReadWrapperCallback;
import cn.com.heaton.blelibrary.ble.callback.wrapper.WriteWrapperCallback;
import cn.com.heaton.blelibrary.ble.model.BleDevice;
import cn.com.heaton.blelibrary.ble.request.ConnectRequest;
import cn.com.heaton.blelibrary.ble.request.MtuRequest;
import cn.com.heaton.blelibrary.ble.request.NotifyRequest;
import cn.com.heaton.blelibrary.ble.request.ReadRequest;
import cn.com.heaton.blelibrary.ble.request.Rproxy;
import cn.com.heaton.blelibrary.ble.request.WriteRequest;
import cn.com.heaton.blelibrary.ble.utils.ByteUtils;
import cn.com.heaton.blelibrary.ota.OtaListener;


public final class BleRequestImpl<T extends BleDevice> {

    private final static String TAG = BleRequestImpl.class.getSimpleName();

    private static BleRequestImpl instance;
    private Handler handler = BleHandler.of();
    private Ble.Options options;
    private Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private final Object locker = new Object();
    private List<BluetoothGattCharacteristic> notifyCharacteristics = new ArrayList<>();//Notification attribute callback array
    private int notifyIndex = 0;//Notification feature callback list
    private BluetoothGattCharacteristic otaWriteCharacteristic;//Ota ble send the object
    private boolean otaUpdating = false;//Whether the OTA is updated
    private Map<String, BluetoothGattCharacteristic> writeCharacteristicMap = new HashMap<>();
    private Map<String, BluetoothGattCharacteristic> readCharacteristicMap = new HashMap<>();
    //Multiple device connections must put the gatt object in the collection
    private Map<String, BluetoothGatt> gattHashMap = new HashMap<>();
    //The address of the connected device
    private List<String> connectedAddressList = new ArrayList<>();
    private ConnectWrapperCallback connectWrapperCallback;
    private NotifyWrapperCallback notifyWrapperCallback;
    private MtuWrapperCallback mtuWrapperCallback;
    private ReadRssiWrapperCallback readRssiWrapperCallback;
    private ReadWrapperCallback readWrapperCallback;
    private WriteWrapperCallback writeWrapperCallback;
    private OtaListener otaListener;//Ota update operation listener

    private BleRequestImpl(){}

    //在各种状态回调中发现连接更改或服务
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                            int newState) {
            BluetoothDevice device = gatt.getDevice();
            //remove timeout callback
            cancelTimeout(device.getAddress());
            //There is a problem here Every time a new object is generated that causes the same device to be disconnected and the connection produces two objects
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedAddressList.add(device.getAddress());
                    if (connectWrapperCallback != null){
                        connectWrapperCallback.onConnectionChanged(device, BleStates.BleStatus.CONNECTED);
                    }
                    BleLog.i(TAG, "onConnectionStateChange:>>>>>>>>CONNECTED.");
                    BluetoothGatt bluetoothGatt = gattHashMap.get(device.getAddress());
                    if (null != bluetoothGatt){
                        // Attempts to discover services after successful connection.
                        Log.i(TAG, "Attempting to start service discovery");
                        bluetoothGatt.discoverServices();
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    BleLog.i(TAG, "Disconnected from GATT server.");
                    if (connectWrapperCallback != null){
                        connectWrapperCallback.onConnectionChanged(device, BleStates.BleStatus.DISCONNECT);
                    }
                    close(device.getAddress());
                }
            } else {
                //Occurrence 133 or 257 19 Equal value is not 0: Connection establishment failed due to protocol stack
                BleLog.e(TAG, "onConnectionStateChange>>>>>>>>: " + "Connection status is abnormal:" + status);
                close(device.getAddress());
                if (connectWrapperCallback != null){
                    connectWrapperCallback.onConnectException(device);
                    connectWrapperCallback.onConnectionChanged(device, BleStates.BleStatus.DISCONNECT);
                }
            }

        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public void onMtuChanged(android.bluetooth.BluetoothGatt gatt, int mtu, int status){
            if (gatt != null && gatt.getDevice() != null) {
                BleLog.e(TAG, "onMtuChanged mtu=" + mtu + ",status=" + status);
                if (null != mtuWrapperCallback){
                    mtuWrapperCallback.onMtuChanged(gatt.getDevice(), mtu, status);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (connectWrapperCallback != null) {
                    connectWrapperCallback.onServicesDiscovered(gatt.getDevice());
                }
                //Empty the notification attribute list
                notifyCharacteristics.clear();
                notifyIndex = 0;
                //Start setting notification feature
                displayGattServices(gatt.getDevice(), getSupportedGattServices(gatt.getDevice().getAddress()));
            } else {
                BleLog.e(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            BleLog.d(TAG, "onCharacteristicRead:" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (null != readWrapperCallback){
                    readWrapperCallback.onReadSuccess(gatt.getDevice(), characteristic);
                }
            }else {
                if (null != readWrapperCallback){
                    readWrapperCallback.onReadFailed(gatt.getDevice(), "读取失败,status:"+status);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            BleLog.i(TAG, "--------write success----- status:" + status);
            synchronized (locker) {
                BleLog.i(TAG, gatt.getDevice().getAddress() + " -- onCharacteristicWrite: " + status);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (null != writeWrapperCallback){
                        writeWrapperCallback.onWriteSuccess(gatt.getDevice(), characteristic);
                    }
                    if (options.uuid_ota_write_cha.equals(characteristic.getUuid())) {
                        if (otaListener != null) {
                            otaListener.onWrite();
                        }
                    }
                }else {
                    if (null != writeWrapperCallback){
                        writeWrapperCallback.onWiteFailed(gatt.getDevice(), "写入失败,status:"+status);
                    }
                }
            }
        }

        /**
         * 当连接成功的时候会回调这个方法，这个方法可以处理发送密码或者数据分析
         * 当setnotify（true）被设置时，如果MCU（设备端）上的数据改变，则该方法被回调。
         * @param gatt 蓝牙gatt对象
         * @param characteristic 蓝牙通知特征对象
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            synchronized (locker) {
                if (gatt.getDevice() == null)return;
                BleLog.i(TAG, gatt.getDevice().getAddress() + " -- onCharacteristicChanged: "
                        + (characteristic.getValue() != null ? ByteUtils.BinaryToHexString(characteristic.getValue()) : ""));
                if (notifyWrapperCallback != null) {
                    notifyWrapperCallback.onChanged(gatt.getDevice(), characteristic);
                }
                if (options.uuid_ota_write_cha.equals(characteristic.getUuid()) || options.uuid_ota_notify_cha.equals(characteristic.getUuid())) {
                    if (otaListener != null) {
                        otaListener.onChange(characteristic.getValue());
                    }
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            UUID uuid = descriptor.getCharacteristic().getUuid();
            BleLog.i(TAG, "onDescriptorWrite");
            BleLog.i(TAG, "descriptor_uuid:" + uuid);
            synchronized (locker) {
                BleLog.w(TAG, " -- onDescriptorWrite: " + status);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (notifyCharacteristics.size() > 0 && notifyIndex < notifyCharacteristics.size()) {
                        setCharacteristicNotification(gatt.getDevice().getAddress(), true);
                    } else {
                        BleLog.i(TAG, "====setCharacteristicNotification is completed===");
                        if (notifyWrapperCallback != null) {
                            if (Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                    || Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)){
                                notifyWrapperCallback.onNotifySuccess(gatt.getDevice());
                            }else if (Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)){
                                notifyWrapperCallback.onNotifyCanceled(gatt.getDevice());
                            }

                        }
                    }
                }
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            UUID uuid = descriptor.getCharacteristic().getUuid();
            BleLog.i(TAG, "onDescriptorRead");
            BleLog.i(TAG, "descriptor_uuid:" + uuid);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            BleLog.i(TAG, "rssi="+rssi);
            if (null != readRssiWrapperCallback){
                readRssiWrapperCallback.onReadRssiSuccess(gatt.getDevice(), rssi);
            }
        }
    };

    /**
     *
     * @return 已经连接的设备集合
     */
    public List<BluetoothDevice> getConnectedDevices() {
        if (bluetoothManager == null) return null;
        return bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
    }

    public static BleRequestImpl getBleRequest(){
        if (instance == null){
            instance = new BleRequestImpl();
        }
        return instance;
    }

    boolean initialize(Context context) {
        this.connectWrapperCallback = Rproxy.getRequest(ConnectRequest.class);
        this.notifyWrapperCallback = Rproxy.getRequest(NotifyRequest.class);
        this.mtuWrapperCallback = Rproxy.getRequest(MtuRequest.class);
        this.readWrapperCallback = Rproxy.getRequest(ReadRequest.class);
        this.readRssiWrapperCallback = Rproxy.getRequest(ReadRssiWrapperCallback.class);
        this.writeWrapperCallback = Rproxy.getRequest(WriteRequest.class);
        this.context = context;
        this.options = Ble.options();

        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                BleLog.e(TAG, "Unable to initBLE BluetoothManager.");
                return false;
            }
        }
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            BleLog.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }
        return true;
    }

    void release() {
        connectWrapperCallback = null;
        mtuWrapperCallback = null;
        notifyWrapperCallback = null;
        readRssiWrapperCallback = null;
        readWrapperCallback = null;
        writeWrapperCallback = null;
        handler.removeCallbacksAndMessages(null);
        BleLog.e(TAG, "BleRequestImpl is released");
    }

    public void cancelTimeout(String address){
        handler.removeCallbacksAndMessages(address);
    }

    /**
     * 连接蓝牙
     *
     * @param address Bluetooth address
     * @return Connection result
     */
    public boolean connect(final String address) {
        if (connectedAddressList.contains(address)) {
            BleLog.d(TAG, "This is device already connected.");
            return true;
        }
        if (bluetoothAdapter == null) {
            BleLog.w(TAG, "BluetoothAdapter not initialized");
            return false;
        }
        // getRemoteDevice(address) will throw an exception if the device address is invalid,
        // so it's necessary to check the address
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            BleLog.d(TAG, "the device address is invalid");
            return false;
        }
        // Previously connected device. Try to reconnect. ()
        final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            BleLog.d(TAG, "no device");
            return false;
        }
        //10s after the timeout prompt
        HandlerCompat.postDelayed(handler, new Runnable() {
            @Override
            public void run() {
                connectWrapperCallback.onConnectTimeOut(device);
                close(device.getAddress());
            }
        }, device.getAddress(), options.connectTimeout);
        if (connectWrapperCallback != null){
            connectWrapperCallback.onConnectionChanged(device, BleStates.BleStatus.CONNECTING);
        }
        // We want to directly connect to the device, so we are setting the autoConnect parameter to false TodO()修改失去连接是否自动连接设置
        BluetoothGatt bluetoothGatt = device.connectGatt(context, this.options.autoConnect, gattCallback);
        if (bluetoothGatt != null) {
            gattHashMap.put(address, bluetoothGatt);
            BleLog.d(TAG, "Trying to create a new connection.");
            return true;
        }
        return false;
    }

    /**
     * 断开蓝牙
     *
     * @param address 蓝牙地址
     */
    public void disconnect(String address) {
        if (verifyParams(address)) return;
        boolean isValidAddress = BluetoothAdapter.checkBluetoothAddress(address);
        if (!isValidAddress) {
            BleLog.e(TAG, "the device address is invalid");
            return;
        }
        gattHashMap.get(address).disconnect();
        notifyIndex = 0;
        notifyCharacteristics.clear();
        writeCharacteristicMap.remove(address);
        readCharacteristicMap.remove(address);
        otaWriteCharacteristic = null;
    }

    /**
     * 清除蓝牙蓝牙连接设备的指定蓝牙地址
     *
     * @param address 蓝牙地址
     */
    public void close(String address) {
        connectedAddressList.remove(address);
        if (gattHashMap.get(address) != null) {
            gattHashMap.get(address).close();
            gattHashMap.remove(address);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public boolean setMtu(String address, int mtu){
        if (verifyParams(address)) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            if(mtu>20){
                if (gattHashMap.get(address) != null) {
                    boolean result = gattHashMap.get(address).requestMtu(mtu);
                    BleLog.d(TAG,"requestMTU "+mtu+" result="+result);
                    return result;
                }
            }
        }
        return false;
    }

    /**
     * 清除所有可连接的设备
     */
    public void close() {
        if (connectedAddressList == null) return;
        for (String address : connectedAddressList) {
            if (gattHashMap.get(address) != null) {
                gattHashMap.get(address).close();
            }
        }
        gattHashMap.clear();
        connectedAddressList.clear();
    }

    /**
     * 清理蓝牙缓存
     */
    public boolean refreshDeviceCache(String address) {
        BluetoothGatt gatt = gattHashMap.get(address);
        if (gatt != null) {
            try {
                Method localMethod = gatt.getClass().getMethod(
                        "refresh", new Class[0]);
                if (localMethod != null) {
                    boolean bool = ((Boolean) localMethod.invoke(
                            gatt, new Object[0])).booleanValue();
                    return bool;
                }
            } catch (Exception localException) {
                BleLog.i(TAG, "An exception occured while refreshing device");
            }
        }
        return false;
    }


    /**
     * 写入数据
     *
     * @param address 蓝牙地址
     * @param value   发送的字节数组
     * @return 写入是否成功(这个是客户端的主观认为)
     */
    public boolean wirteCharacteristic(String address, byte[] value) {
        if (verifyParams(address)) return false;
        BluetoothGattCharacteristic gattCharacteristic = writeCharacteristicMap.get(address);
        if (gattCharacteristic != null) {
            try {
                if (options.uuid_write_cha.equals(gattCharacteristic.getUuid())) {
                    gattCharacteristic.setValue(value);
                    boolean result = gattHashMap.get(address).writeCharacteristic(gattCharacteristic);
                    BleLog.d(TAG, address + " -- write data:" + Arrays.toString(value));
                    BleLog.d(TAG, address + " -- write result:" + result);
                    return result;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;

    }

    /**
     * 读取数据
     *
     * @param address 蓝牙地址
     * @return 读取是否成功(这个是客户端的主观认为)
     */
    public boolean readCharacteristic(String address) {
        if (verifyParams(address)) return false;
        BluetoothGattCharacteristic gattCharacteristic = readCharacteristicMap.get(address);
        if (gattCharacteristic != null) {
            try {
                if (options.uuid_read_cha.equals(gattCharacteristic.getUuid())) {
                    boolean result = gattHashMap.get(address).readCharacteristic(gattCharacteristic);
                    BleLog.d(TAG, address + " -- read result:" + result);
                    return result;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private boolean verifyParams(String address) {
        if (bluetoothAdapter == null || gattHashMap.get(address) == null) {
            BleLog.e(TAG, "BluetoothAdapter or BluetoothGatt is null");
            return true;
        }
        return false;
    }

    /**
     * 读取远程RSSI
     * @param address 蓝牙地址
     * @return 是否读取RSSI成功(这个是客户端的主观认为)
     */
    public boolean readRssi(String address) {
        if (verifyParams(address)) return false;
        BluetoothGattCharacteristic gattCharacteristic = readCharacteristicMap.get(address);
        if (gattCharacteristic != null) {
            try {
                boolean result = gattHashMap.get(address).readRemoteRssi();
                BleLog.d(TAG, address + " -- read result:" + result);
                return result;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;

    }

    /**
     * 启用或禁用给定特征的通知
     *
     * @param address        蓝牙地址
     * @param enabled   是否设置通知使能
     */
    public void setCharacteristicNotification(String address, boolean enabled) {
        if (verifyParams(address)) return;
        if (notifyCharacteristics.size() > 0 && notifyIndex < notifyCharacteristics.size()){
            BluetoothGattCharacteristic characteristic = notifyCharacteristics.get(notifyIndex++);
            setCharacteristicNotificationInternal(gattHashMap.get(address), characteristic, enabled);
        }
    }

    private void setCharacteristicNotificationInternal(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, boolean enabled){
        gatt.setCharacteristicNotification(characteristic, enabled);
        //If the number of descriptors in the eigenvalue of the notification is greater than zero
        if (characteristic.getDescriptors().size() > 0) {
            //Filter descriptors based on the uuid of the descriptor
            List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
            for(BluetoothGattDescriptor descriptor : descriptors){
                if (descriptor != null) {
                    //Write the description value
                    if((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0){
                        descriptor.setValue(enabled?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                    }else if((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0){
                        descriptor.setValue(enabled?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                    }
                    gatt.writeDescriptor(descriptor);
                    BleLog.i(TAG, "setCharacteristicNotificationInternal is "+enabled);
                }
            }
        }
    }

    /**
     * @param device 蓝牙
     * @param gattServices 蓝牙服务集合
     */
    private void displayGattServices(final BluetoothDevice device, List<BluetoothGattService> gattServices) {
        if (gattServices == null || device == null) {
            BleLog.e(TAG, "displayGattServices gattServices or device is null");
//            close(device.getAddress());
            return;
        }
        if (gattServices.isEmpty()) {
            BleLog.e(TAG, "displayGattServices gattServices size is 0");
            disconnect(device.getAddress());
            return;
        }
        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            String uuid = gattService.getUuid().toString();
            BleLog.d(TAG, "discovered gattServices: " + uuid);
            if (uuid.equals(options.uuid_service.toString()) || isContainUUID(uuid)) {
                BleLog.d(TAG, "service_uuid: " + uuid);
                List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
                for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                    /*int charaProp = gattCharacteristic.getProperties();
                    if ((charaProp & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        Log.e(TAG, "The readable UUID for gattCharacteristic is:" + gattCharacteristic.getUuid());
                        readCharacteristicMap.put(address, gattCharacteristic);
                    }
                    if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        Log.e(TAG, "The writable UUID for gattCharacteristic is:" + gattCharacteristic.getUuid());
                        writeCharacteristicMap.put(address, gattCharacteristic);
                    }
                    if ((charaProp & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        Log.e(TAG, "The PROPERTY_NOTIFY characteristic's UUID:" + gattCharacteristic.getUuid());
                        mNotifyCharacteristics.add(gattCharacteristic);
                    }
                    if((charaProp & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0){
                        Log.e(TAG, "The PROPERTY_INDICATE characteristic's UUID:" + gattCharacteristic.getUuid());
                        mNotifyCharacteristics.add(gattCharacteristic);
                    }*/
                    uuid = gattCharacteristic.getUuid().toString();
                    BleLog.d(TAG, "Characteristic_uuid: " + uuid);
                    if (uuid.equals(options.uuid_write_cha.toString())) {
                        BleLog.e("mWriteCharacteristic", uuid);
                        writeCharacteristicMap.put(device.getAddress(), gattCharacteristic);
                        //Notification feature
                    } if (uuid.equals(options.uuid_read_cha.toString())) {
                        BleLog.e("mReadCharacteristic", uuid);
                        readCharacteristicMap.put(device.getAddress(), gattCharacteristic);
                        //Notification feature
                    } if ((gattCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        notifyCharacteristics.add(gattCharacteristic);
                        BleLog.e("mNotifyCharacteristics", "PROPERTY_NOTIFY");
                    } if((gattCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0){
                        notifyCharacteristics.add(gattCharacteristic);
                        BleLog.e("mNotifyCharacteristics", "PROPERTY_INDICATE");
                    }
                }
                /*//Really set up notifications
                if (notifyCharacteristics != null && notifyCharacteristics.size() > 0) {
                    BleLog.e("setCharaNotification", "setCharaNotification");
                    setCharacteristicNotification(address, notifyCharacteristics.get(notifyIndex++), true);
                }*/
                if (null != connectWrapperCallback){
                    connectWrapperCallback.onReady(device);
                }
            }
        }
    }

    //是否包含该uuid
    private boolean isContainUUID(String uuid) {
        for (UUID u : options.uuid_services_extra){
            if(u != null && uuid.equals(u.toString())){
                return true;
            }
        }
        return false;
    }

    /**
     * 获取可写特征对象
     * @param address 蓝牙地址
     * @return  可写特征对象
     */
    public BluetoothGattCharacteristic getWriteCharacteristic(String address) {
        synchronized (locker) {
            if (writeCharacteristicMap != null) {
                return writeCharacteristicMap.get(address);
            }
            return null;
        }
    }

    /**
     * 获取可读特征对象
     * @param address 蓝牙地址
     * @return  可读特征对象
     */
    public BluetoothGattCharacteristic getReadCharacteristic(String address) {
        synchronized (locker) {
            if (readCharacteristicMap != null) {
                return readCharacteristicMap.get(address);
            }
            return null;
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This
     * should be invoked only after {@code BluetoothGatt#discoverServices()}
     * completes successfully.
     *
     * @param address ble address
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices(String address) {
        if (gattHashMap.get(address) == null)
            return null;

        return gattHashMap.get(address).getServices();
    }

    /**
     * 写入OTA数据
     *
     * @param address 蓝牙地址
     * @param value   发送字节数组
     * @return 写入是否成功
     */
    public boolean writeOtaData(String address, byte[] value) {
        if (verifyParams(address)) return false;
        try {
            if (otaWriteCharacteristic == null) {
                otaUpdating = true;
                BluetoothGattService bluetoothGattService = gattHashMap.get(address).getService(options.uuid_ota_service);
                if (bluetoothGattService == null) {
                    return false;
                } else {
                    BluetoothGattCharacteristic mOtaNotifyCharacteristic = bluetoothGattService.getCharacteristic(options.uuid_ota_notify_cha);
                    if (mOtaNotifyCharacteristic != null) {
                        gattHashMap.get(address).setCharacteristicNotification(mOtaNotifyCharacteristic, true);
                    }
                    otaWriteCharacteristic = bluetoothGattService.getCharacteristic(options.uuid_ota_write_cha);
                }

            }
            if (otaWriteCharacteristic != null && options.uuid_ota_write_cha.equals(otaWriteCharacteristic.getUuid())) {
                otaWriteCharacteristic.setValue(value);
                boolean result = writeCharacteristic(gattHashMap.get(address), otaWriteCharacteristic);
                BleLog.d(TAG, address + " -- write data:" + Arrays.toString(value));
                BleLog.d(TAG, address + " -- write result:" + result);
                return result;
            }
            return true;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            close();
            return false;
        }
    }

    //The basic method of writing data
    public boolean writeCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        synchronized (locker) {
            return !(gatt == null || characteristic == null) && gatt.writeCharacteristic(characteristic);
        }
    }

    /**
     * OTA升级完成
     */
    public void otaUpdateComplete() {
        otaUpdating = false;
    }

    /**
     * 设置OTA是否正在升级
     *
     * @param updating 升级状态
     */
    public void setOtaUpdating(boolean updating) {
        this.otaUpdating = updating;
    }

    /**
     * 设置OTA更新状态监听
     *
     * @param otaListener 监听对象
     */
    public void setOtaListener(OtaListener otaListener) {
        this.otaListener = otaListener;
    }
}
