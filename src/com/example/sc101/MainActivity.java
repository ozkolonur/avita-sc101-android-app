package com.example.sc101;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity {
	BluetoothAdapter mBluetoothAdapter;
	BluetoothDevice mmDevice;
	BluetoothSocket mmSocket;
	OutputStream mmOutputStream;
	InputStream mmInputStream;
	Thread workerThread;
	volatile boolean stopWorker;
	byte[] readBuffer;
	int readBufferPosition;
	TextView measurementValue;
	TextView status;
	String latestMeasurement;
	boolean measurementLocked = true;
	
	/* A measurement is consist of one weight value, 
	 * and a fractional weight value in kg: WW.ff ex. 72.50 kg */
	public static final int QUEUE_SIZE=10;
	static int weights[] = new int[QUEUE_SIZE];
	static int fracs[] = new int[QUEUE_SIZE];
	static int weightsArrayPosition = -1;
	
	public static String extract_measurement(byte[] bytes) {
		/* If you receive a 5 bytes packet from device, 
		 * bytes[2] and bytes[3] is your weight */
		int weight = Integer.parseInt(String.format("%x", bytes[2]));
		int frac = Integer.parseInt(String.format("%x", bytes[3]));
		
		if (weightsArrayPosition > 8 || weightsArrayPosition == -1)
			weightsArrayPosition = 0;
		else
			weightsArrayPosition++;
		
		weights[weightsArrayPosition] = weight;
		fracs[weightsArrayPosition] = frac;
		
		/* weight data is added to weights array, and 
		 * we return current value for screen updating etc. */
		return String.format("%x", bytes[2])+","+String.format("%x", bytes[3]);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
	    Button connectButton = (Button)findViewById(R.id.connect);
	    Button saveButton = (Button)findViewById(R.id.save);
	    measurementValue = (TextView)findViewById(R.id.measurementValue);
	    status = (TextView)findViewById(R.id.status);


	    connectButton.setOnClickListener(new View.OnClickListener()
	    {
	        public void onClick(View v)
	        {
	            try 
	            {
	                findBT();
	                openBT();
	            }
	            catch (IOException ex) { }
	        }
	    });

	    saveButton.setOnClickListener(new View.OnClickListener()
	    {
	        public void onClick(View v)
	        {
	            try 
	            {
	                sendData();
	            }
	            catch (IOException ex) { }
	        }
	    });
	    
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
	void findBT()
	{
	    mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
	    if(mBluetoothAdapter == null)
	    {
	        status.setText("No bluetooth adapter available");
	    }

	    if(!mBluetoothAdapter.isEnabled())
	    {
	        Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
	        startActivityForResult(enableBluetooth, 0);
	    }

	    Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
	    if(pairedDevices.size() > 0)
	    {
	        for(BluetoothDevice device : pairedDevices)
	        {
	            if(device.getName().equals("AViTA")) 
	            {
	                mmDevice = device;
	                break;
	            }
	        }
	    }
	    status.setText("Bluetooth Device Found");
	}

	void openBT() throws IOException
	{
	    UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
	    mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);        
	    mmSocket.connect();
	    mmOutputStream = mmSocket.getOutputStream();
	    mmInputStream = mmSocket.getInputStream();

	    beginListenForData();

	    status.setText("Connected!");
	}

	void beginListenForData()
	{
	    final Handler handler = new Handler(); 
	    final byte delimiter = 10; //This is the ASCII code for a newline character

	    stopWorker = false;
	    readBufferPosition = 0;
	    readBuffer = new byte[4094];
	    workerThread = new Thread(new Runnable()
	    {
	        public void run()
	        {                
	           while(!Thread.currentThread().isInterrupted() && !stopWorker)
	           {
	                try 
	                {
	                    int bytesAvailable = mmInputStream.available();                        
	                    if(bytesAvailable > 0)
	                    {
	                        byte[] packetBytes = new byte[bytesAvailable];
	                        mmInputStream.read(packetBytes);
	                        if (bytesAvailable == 5) {
	                        	latestMeasurement = extract_measurement(packetBytes);
	                        }
	                        for(int i=0;i<bytesAvailable;i++)
	                        {
	                            byte b = packetBytes[i];
	                            if(b == delimiter)
	                            {
								     byte[] encodedBytes = new byte[readBufferPosition];
								     System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
								     final String data = new String(encodedBytes, "US-ASCII");
								     readBufferPosition = 0;

	                                handler.post(new Runnable()
	                                {
	                                    public void run()
	                                    {
	                                        measurementValue.setText(latestMeasurement);
	                                    }
	                                });
	                            }
	                            else
	                            {
	                                readBuffer[readBufferPosition++] = b;
	                            }
	                        }
	                    }
	                    measurementLocked=true;
	                    
	    				for(int t=0; t<(QUEUE_SIZE); t++)
	    				{
	    					if ((weights[0] != weights[t]) || (fracs[0] != fracs[t]))
	    						measurementLocked=false;
	    					//Log.d("Sc101", String.format("==%d==weight is %d %d \n", t, weights[t], fracs[t]));
	    				}
	    				
	    				if (measurementLocked && (weights[0] != 0))
	    				{
	    					Log.d("Sc101", String.format("LOCKED %d %d \n", weights[1], fracs[1]));
	    					
                            handler.post(new Runnable()
                            {
                                public void run()
                                {
                                    measurementValue.setText(String.format("%d.%d", weights[1], fracs[1]));
                                }
                            });
                            
	    	                closeBT();
	    				}
	                } 
	                catch (IOException ex) 
	                {
	                    stopWorker = true;
	                }
	           }
	        }
	    });

	    workerThread.start();
	}

	void sendData() throws IOException
	{
		/* TODO: save data to somewhere */
	}

	void closeBT() throws IOException
	{
	    stopWorker = true;
	    mmOutputStream.close();
	    mmInputStream.close();
	    mmSocket.close();
	    //status.setText("Bluetooth Closed");
	}
	

}
