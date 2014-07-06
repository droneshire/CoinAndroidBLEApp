package com.example.blecoinapp;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

public class MainActivity extends Activity implements BluetoothAdapter.LeScanCallback {
    private static final String TAG = "COIN";

    private static final String DEVICE_NAME = "Coin";

    /* Coin BLE Service */
    private static final UUID COIN_SERVICE = UUID.fromString("3870cd80-fc9c-11e1-a21f-0800200c9a66");
    private static final UUID COIN_TX = UUID.fromString("E788D73B-E793-4D9E-A608-2F2BAFC59A00");
    private static final UUID COIN_RX = UUID.fromString("4585C102-7784-40B4-88E1-3CB5C4FD37A3");
    //private static final UUID COIN_RX_BUFFER_COUNT = UUID.fromString("11846C20-6630-11E1-B86C-0800200C9A66");
    //private static final UUID COIN_RX_BUFFER_CLEAR = UUID.fromString("DAF75440-6EBA-11E1-B0C4-0800200C9A66");

    private BluetoothAdapter mBluetoothAdapter;
    private SparseArray<BluetoothDevice> mDevices;

    private BluetoothGatt mConnectedGatt;

    private TextView mReceive, mStatus;
    
    private EditText mSend;

    private ProgressDialog mProgress;
    
    private boolean sendData = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_main);
        setProgressBarIndeterminate(true);

        /*
         * We are going to display the results in some text fields
         */
        mReceive = (TextView) findViewById(R.id.text_RX);
        mStatus = (TextView) findViewById(R.id.textStatus);
        mSend = (EditText) findViewById(R.id.edit_TX);

        /*
         * Bluetooth in Android 4.3 is accessed via the BluetoothManager, rather than
         * the old static BluetoothAdapter.getInstance()
         */
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = manager.getAdapter();

        mDevices = new SparseArray<BluetoothDevice>();

        /*
         * A progress dialog will be needed while the connection process is
         * taking place
         */
        mProgress = new ProgressDialog(this);
        mProgress.setIndeterminate(true);
        mProgress.setCancelable(false);
    }

    public static byte[] stringToBytesUTFCustom(String str) {
    	 char[] buffer = str.toCharArray();
    	 byte[] b = new byte[buffer.length << 1];
    	 for (int i = 0; i < buffer.length; i++) {
    	 int bpos = i << 1;
    	 b[bpos] = (byte) ((buffer[i]&0xFF00)>>8);
    	 b[bpos + 1] = (byte) (buffer[i]&0x00FF);
    	 }
    	 return b;
    }
    
    //Send button handler
    public void sendBleData(View view) {
    	if(sendData) {
    		BluetoothGattCharacteristic characteristic; 
    		EditText input = (EditText) findViewById(R.id.edit_TX);
    		String sendString = input.getText().toString();
    		byte[] myByte = stringToBytesUTFCustom(sendString);
    		characteristic = mConnectedGatt.getService(COIN_SERVICE)
    					.getCharacteristic(COIN_TX);
    		characteristic.setValue(myByte);
    		mConnectedGatt.writeCharacteristic(characteristic);
    		mStatus.setText("Sending data...");
    	}
    	else {
    		mStatus.setText("Not ready yet");
    	}
    	
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        /*
         * We need to enforce that Bluetooth is first enabled, and take the
         * user to settings to enable it if they have not done so.
         */
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            //Bluetooth is disabled
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            finish();
            return;
        }

        /*
         * Check for Bluetooth LE Support.  In production, our manifest entry will keep this
         * from installing on these devices, but this will allow test devices or other
         * sideloads to report whether or not the feature exists.
         */
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No LE Support.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        clearDisplayValues();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //Make sure dialog is hidden
        mProgress.dismiss();
        //Cancel any scans in progress
        mHandler.removeCallbacks(mStopRunnable);
        mHandler.removeCallbacks(mStartRunnable);
        mBluetoothAdapter.stopLeScan(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        //Disconnect from any active tag connection
        if (mConnectedGatt != null) {
            mConnectedGatt.disconnect();
            mConnectedGatt = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Add the "scan" option to the menu
        getMenuInflater().inflate(R.menu.main, menu);
        //Add any device elements we've discovered to the overflow menu
        for (int i=0; i < mDevices.size(); i++) {
            BluetoothDevice device = mDevices.valueAt(i);
            menu.add(0, mDevices.keyAt(i), 0, device.getName());
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_scan:
                mDevices.clear();
                startScan();
                return true;
            default:
                //Obtain the discovered device to connect with
                BluetoothDevice device = mDevices.get(item.getItemId());
                Log.i(TAG, "Connecting to "+device.getName());
                /*
                 * Make a connection with the device using the special LE-specific
                 * connectGatt() method, passing in a callback for GATT events
                 */
                mConnectedGatt = device.connectGatt(this, false, mGattCallback);
                //Display progress UI
                mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Connecting to "+device.getName()+"..."));
                return super.onOptionsItemSelected(item);
        }
    }

    private void clearDisplayValues() {
        mSend.setText("---");
        mReceive.setText("---");
    }


    private Runnable mStopRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };
    private Runnable mStartRunnable = new Runnable() {
        @Override
        public void run() {
            startScan();
        }
    };

    private void startScan() {
        mBluetoothAdapter.startLeScan(this);
        setProgressBarIndeterminateVisibility(true);

        mHandler.postDelayed(mStopRunnable, 2500);
    }

    private void stopScan() {
        mBluetoothAdapter.stopLeScan(this);
        setProgressBarIndeterminateVisibility(false);
    }

    /* BluetoothAdapter.LeScanCallback */

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        Log.i(TAG, "New LE Device: " + device.getName() + " @ " + rssi);
        /*
         * We are looking for SensorTag devices only, so validate the name
         * that each device reports before adding it to our collection
         */
        if (DEVICE_NAME.equals(device.getName())) {
            mDevices.put(device.hashCode(), device);
            //Update the overflow menu
            invalidateOptionsMenu();
        }
    }

    /*
     * In this callback, we've created a bit of a state machine to enforce that only
     * one characteristic be read or written at a time until all of our sensors
     * are enabled and we are registered to get notifications.
     */
    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        private void setNotify(BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            characteristic = gatt.getService(COIN_SERVICE)
            					.getCharacteristic(COIN_RX);
            //Enable local notifications
            gatt.setCharacteristicNotification(characteristic, true);
            mHandler.sendMessage(Message.obtain(null, MSG_NOTIFY, "Setup to read notifications..."));
        }
        
        private void readFirstRX(BluetoothGatt gatt) {
        	BluetoothGattCharacteristic  c;
        	Log.d(TAG, "Reading RX val once...");
        	c = gatt.getService(COIN_SERVICE)
        				.getCharacteristic(COIN_RX);
        	gatt.readCharacteristic(c);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "Connection State Change: "+status+" -> "+connectionState(newState));
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                /*
                 * Once successfully connected, we must next discover all the services on the
                 * device before we can read and write their characteristics.
                 */
                gatt.discoverServices();
                mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Discovering Services..."));
            } else if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                /*
                 * If at any point we disconnect, send a message to clear the weather values
                 * out of the UI
                 */
                mHandler.sendEmptyMessage(MSG_CLEAR);
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                /*
                 * If there is a failure at any stage, simply disconnect
                 */
                gatt.disconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "Services Discovered: "+status);
            mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Enabling Sensors..."));
            Log.d(TAG,"Reading first byte before notifying...");
            readFirstRX(gatt);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //For each read, pass the data up to the UI thread to update the display
            if (COIN_RX.equals(characteristic.getUuid())) {
                mHandler.sendMessage(Message.obtain(null, MSG_RX_DATA, characteristic));
            }
            //After reading the initial value, next we enable notifications
            setNotify(gatt);
        }
        
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        	mHandler.sendMessage(Message.obtain(null, MSG_TX_DATA, characteristic));
        	Log.d(TAG, "Sent data");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            /*
             * After notifications are enabled, all updates from the device on characteristic
             * value changes will be posted here.  Similar to read, we hand these up to the
             * UI thread to update the display.
             */
            if (COIN_RX.equals(characteristic.getUuid())) {
                mHandler.sendMessage(Message.obtain(null, MSG_RX_DATA, characteristic));
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            Log.d(TAG, "Remote RSSI: "+rssi);
        }

        private String connectionState(int status) {
            switch (status) {
                case BluetoothProfile.STATE_CONNECTED:
                    return "Connected";
                case BluetoothProfile.STATE_DISCONNECTED:
                    return "Disconnected";
                case BluetoothProfile.STATE_CONNECTING:
                    return "Connecting";
                case BluetoothProfile.STATE_DISCONNECTING:
                    return "Disconnecting";
                default:
                    return String.valueOf(status);
            }
        }
    };

    /*
     * We have a Handler to process event results on the main thread
     */
    private static final int MSG_TX_DATA = 101;
    private static final int MSG_RX_DATA = 102;
    private static final int MSG_READY = 103;
    private static final int MSG_NOTIFY = 104;
    private static final int MSG_PROGRESS = 201;
    private static final int MSG_DISMISS = 202;
    private static final int MSG_CLEAR = 301;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            BluetoothGattCharacteristic characteristic;
            switch (msg.what) {
                case MSG_TX_DATA:
                    Log.w(TAG, "Sent TX data");
                    mStatus.setText("Sent data...");
                    break;
                case MSG_RX_DATA:
                    characteristic = (BluetoothGattCharacteristic) msg.obj;
                    if (characteristic.getValue() == null) {
                        Log.w(TAG, "Error obtaining RX value");
                        return;
                    }
                    mStatus.setText("Got data...");
                    updateRxValue(characteristic);
                    break;
                case MSG_READY:
                	sendData = true;
                	Log.d(TAG, "Read services and ready to send data");
                case MSG_NOTIFY:
                	Log.d(TAG, "Notifications set");
                	mStatus.setText("Ready to rx...");
                case MSG_PROGRESS:
                    mProgress.setMessage((String) msg.obj);
                    if (!mProgress.isShowing()) {
                        mProgress.show();
                    }
                    break;
                case MSG_DISMISS:
                    mProgress.hide();
                    break;
                case MSG_CLEAR:
                    clearDisplayValues();
                    break;
            }
        }
    };

    /* Methods to extract sensor data and update the UI */

    private void updateRxValue(BluetoothGattCharacteristic characteristic) {
    	byte[] bytes = characteristic.getValue();
        if (bytes == null) {
        	mReceive.setText("Null");
        	Log.w(TAG,"Null received");
        	return;
        }
        String output = new String(bytes);
        mReceive.setText(output);
    }
}
