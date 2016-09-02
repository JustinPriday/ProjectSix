package com.example.android.sunshine.app.wear;

import android.util.Log;

import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;


//Registered to listen for wear requests for weather update. Request triggers a Sync Adapter update,
//where updated data is transmitted to wear device.

public class WearListenerService extends WearableListenerService {

    private static final String LOG_TAG = WearListenerService.class.getSimpleName();

    private static final String WEATHER_PATH = "/weather";

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent dataEvent : dataEvents) {
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                String path = dataEvent.getDataItem().getUri().getPath();
                Log.d(LOG_TAG, "Got Listener at path " + path);
                if (path.equals(WEATHER_PATH)) {
                    Log.d(LOG_TAG,"Requesting Update");
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}
