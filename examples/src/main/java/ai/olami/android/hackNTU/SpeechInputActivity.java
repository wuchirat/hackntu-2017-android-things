/*
	Copyright 2017, VIA Technologies, Inc. & OLAMI Team.

	http://olami.ai

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
*/

package ai.olami.android.hackNTU;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;

import ai.olami.android.IOlamiSpeechRecognizerListener;
import ai.olami.android.OlamiSpeechRecognizer;
import ai.olami.android.uart.IMicrophoneArrayControlListener;
import ai.olami.android.uart.MicrophoneArrayControl;
import ai.olami.cloudService.APIConfiguration;
import ai.olami.cloudService.APIResponse;
import ai.olami.cloudService.APIResponseData;
import ai.olami.cloudService.SpeechResult;
import ai.olami.core.voice.tts.ITtsListener;
import ai.olami.core.voice.tts.TtsPlayer;
import ai.olami.nli.DescObject;
import ai.olami.nli.NLIResult;


public class SpeechInputActivity extends AppCompatActivity {
    public final static String TAG = "SpeechInputActivity";

    Context mContext = null;

    OlamiSpeechRecognizer mRecognizer = null;
    ITtsListener mTtsListener = null;
    TtsPlayer mTtsPlayer = null;
    MicrophoneArrayControl mMicrophoneArrayControl = null;
    MicrophoneArrayLEDControlHelper mMicrophoneArrayLEDControlHelper = null;

    private final int VOLUME_BAR_MAX_VALUE = 40;
    private final int VOLUME_BAR_MAX_ITEM = 20;
    private final int VOLUME_BAR_ITEM_VALUE = VOLUME_BAR_MAX_VALUE / VOLUME_BAR_MAX_ITEM;

    private Button recordButton;
    private Button cancelButton;

    private TextView voiceVolumeText;
    private TextView voiceVolumeBar;
    private TextView STTText;
    private TextView APIResponseText;
    private TextView recognizeStatusText;

    private boolean mEnableKeyDetect = true;
    private boolean mIsPlayTTS = false;
    boolean mCancelPlayInitializeTTS = false;

    private OlamiSpeechRecognizer.RecognizeState mRecognizeState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_speech_input);

        recordButton = (Button) findViewById(R.id.recordButton);
        cancelButton = (Button) findViewById(R.id.cancelButton);
        voiceVolumeText = (TextView) findViewById(R.id.voiceVolume);
        voiceVolumeBar = (TextView) findViewById(R.id.voiceVolumeBar);
        STTText = (TextView) findViewById(R.id.STTText);
        APIResponseText = (TextView) findViewById(R.id.APIResponse);
        APIResponseText.setMovementMethod(ScrollingMovementMethod.getInstance());
        recognizeStatusText = (TextView) findViewById(R.id.recognizeStatus);

        Typeface custom_font = Typeface.createFromAsset(getAssets(),  "NotoSansTC-Light.otf");
        STTText.setTypeface(custom_font);
        APIResponseText.setTypeface(custom_font);

        recordButton.setOnClickListener(new recordButtonListener());
        cancelButton.setOnClickListener(new cancelButtonListener());
    }

    @Override
    protected void onResume() {
        super.onResume();

        mContext = SpeechInputActivity.this;

        // TTSPlayer初始化
        mTtsListener = new MyTtsListener();
        mTtsPlayer = new TtsPlayer();
        mTtsPlayer.create(mContext);
        mTtsPlayer.setVol(200);

        // 初始化麥克風控制器
        try {
            mMicrophoneArrayControl = MicrophoneArrayControl.create(
                    new MicrophoneArrayListener());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 初始化麥克風LED Helper
        mMicrophoneArrayLEDControlHelper = MicrophoneArrayLEDControlHelper.create(
                mMicrophoneArrayControl);

        init();
    }

    @Override
    protected void onPause() {
        super.onPause();

        releaseRecognizer();

        if (mMicrophoneArrayControl != null) {
            try {
                mMicrophoneArrayControl.closeUart();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (mTtsPlayer != null) {
            mTtsPlayer.stop();
            mTtsPlayer.destroy();
        }
    }

    // 執行歐拉蜜之前，先確認相關環境，包含網路、時間校正
    private void init() {
        final int delayTime = 15000;

        new Thread(new Runnable() {
            public void run() {
                try {
                    Log.i(TAG, "isNetworkConnected(): "+ isNetworkConnected()
                            +", isConnectedToServer(): "+ isConnectedToServer("http://www.baidu.com/", 3000));
                    // 確認裝置是否已經連線至網路
                    while (!isNetworkConnected() || !isConnectedToServer("http://www.baidu.com/", 3000)) {
                        mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                                MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.ERROR);

                        String TTSStr = "歐拉蜜無法連接網路，請重新確認網路環境";
                        mTtsPlayer.playText(mContext, TTSStr, mTtsListener, false);
                        sleep(delayTime);
                    }

                    mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                            MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.INITIALIZING);

                    // 初始化時間，確認裝置已經透過網路自動校正時間
                    while (!checkDeviceTime()) {
                        String TTSStr = "歐拉蜜正在初始化，請稍後";
                        mTtsPlayer.playText(mContext, TTSStr, mTtsListener, false);
                        sleep(delayTime);
                    }

                    String url = "";
                    if (Config.getLocalizeOption() == APIConfiguration
                            .LOCALIZE_OPTION_TRADITIONAL_CHINESE) {
                        url = "https://tw.olami.ai/cloudservice/api";
                    } else if (Config.getLocalizeOption() == APIConfiguration
                            .LOCALIZE_OPTION_SIMPLIFIED_CHINESE) {
                        url = "https://cn.olami.ai/cloudservice/api";
                    }
                    // 確認裝置是否可連線至歐拉蜜伺服器
                    while (!isConnectedToServer(url, 5000)) {
                        mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                                MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.ERROR);

                        String TTSStr = "無法連線至歐拉蜜伺服器，請重新確認網路環境";
                        mTtsPlayer.playText(mContext, TTSStr, mTtsListener, false);
                        sleep(delayTime);
                    }

                    APIConfiguration config = new APIConfiguration(
                            Config.getAppKey(), Config.getAppSecret(), Config.getLocalizeOption());
                    // 初始化OlamiSpeechRecognizer物件
                    try {
                        mRecognizer = OlamiSpeechRecognizer.create(
                                new SpeechRecognizerListener(),
                                config,
                                SpeechInputActivity.this);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    // * Advanced setting example.
                    //   These are also optional steps, so you can skip these
                    //   (or any one of these) to use default setting(s).
                    // ------------------------------------------------------------------
                    // * You can set the length of end time of the VAD in milliseconds
                    //   to stop voice recording automatically.
                    mRecognizer.setLengthOfVADEnd(2500);
                    // * You can set the frequency in milliseconds of the recognition
                    //   result query, then the recognizer client will query the result
                    //   once every milliseconds you set.
                    mRecognizer.setResultQueryFrequency(300);
                    // * You can set audio length in milliseconds to upload, then
                    //   the recognizer client will upload parts of audio once every
                    //   milliseconds you set.
                    mRecognizer.setSpeechUploadLength(300);
                    // ------------------------------------------------------------------

                    // Initialize volume bar of the input audio.
                    voiceVolumeChangeHandler(0);

                    // 啟用關鍵字偵測
                    mRecognizer.enableKeywordDetect();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void releaseRecognizer() {
        if (mRecognizer != null) {
            // * Release the recognizer when program stops or exits.
            mRecognizer.release();
            mRecognizer = null;
        }
    }

    // 確認是否連線至伺服器
    private boolean isConnectedToServer(String url, int timeout) {
        try{
            URL myUrl = new URL(url);
            URLConnection connection = myUrl.openConnection();
            connection.setConnectTimeout(timeout);
            connection.connect();
            return true;
        } catch (Exception e) {
            // Handle your exceptions
            return false;
        }
    }

    // 確認裝置使否已經連線至網路
    private boolean isNetworkConnected(){
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();
        return isConnected;
    }

    // 確認裝置時間是否正確
    private boolean checkDeviceTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy");

        long returnNTPTime = getNTPServerTime("time.stdtime.gov.tw")
                .getMessage()
                .getTransmitTimeStamp()
                .getTime();
        String NTPServerCurrentYear = sdf.format(new Date(returnNTPTime));
        String deviceCurrentYear = sdf.format(new Date(System.currentTimeMillis()));
        Log.i(TAG, "NTPServerCurrentYear: "+ NTPServerCurrentYear +", deviceCurrentYear: "+ deviceCurrentYear);

        if (deviceCurrentYear.equals(NTPServerCurrentYear)) {
            return true;
        } else {
            return false;
        }
    }

    //  取得NTP時間
    private TimeInfo getNTPServerTime(String hostname) {
        NTPUDPClient timeClient;
        InetAddress inetAddress;
        TimeInfo timeInfo = null;

        try {
            timeClient = new NTPUDPClient();
            timeClient.setDefaultTimeout(5000);
            inetAddress = InetAddress.getByName(hostname);
            timeInfo = timeClient.getTime(inetAddress);
        } catch (UnknownHostException uhe) {
            uhe.printStackTrace();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }

        return timeInfo;
    }

    protected class recordButtonListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            // Get current voice recording state.
            mRecognizeState = mRecognizer.getRecognizeState();

            // Check to see if we should start recording or stop manually.
            if (mRecognizeState == OlamiSpeechRecognizer.RecognizeState.STOPPED
                    || mRecognizeState == OlamiSpeechRecognizer.RecognizeState.WAITING_FOR_DETECT) {
                try {
                    // * Request to start voice recording and recognition.
                    mRecognizer.OlamiIsWakeUp(true);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                recordButton.setEnabled(false);
            } else if (mRecognizeState == OlamiSpeechRecognizer.RecognizeState.PROCESSING) {
                // * Request to stop voice recording when manually stop,
                //   and then wait for the final recognition result.
                mRecognizer.stop();
            }
        }
    }

    private class cancelButtonListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            // * Issue to cancel all process including voice recording
            //   and speech recognition.
            mRecognizer.cancel();
        }
    }

    /**
     * This is a callback listener example.
     *
     * You should implement the IOlamiSpeechRecognizerListener
     * to get all callbacks and assign the instance of your listener class
     * into the recognizer instance of OlamiSpeechRecognizer.
     */
    private class SpeechRecognizerListener implements IOlamiSpeechRecognizerListener {
        // * Implement override method to get callback when the recognize
        //   process state changes.
        @Override
        public void onRecognizeStateChange(
                OlamiSpeechRecognizer.RecognizeState state,
                boolean isPlayTTS
        ) {
            String statusStr = getString(R.string.RecognizeState) +" : ";
            String buttonStr = "";
            mRecognizeState = state;

            if (state == OlamiSpeechRecognizer.RecognizeState.STOPPED) {
                statusStr += getString(R.string.RecognizeState_STOPPED);
                Log.i(TAG, statusStr);
                recognizeStateHandler(statusStr);

                buttonStr = getString(R.string.recordButton_start);
                recordButtonChangeHandler(true, buttonStr);
                cancelButtonChangeHandler(View.INVISIBLE, "");
            } else if (state == OlamiSpeechRecognizer.RecognizeState.DETECT_INITIALIZING) {
                statusStr += getString(R.string.KeywordDetect_INIRIALIZING) +"...";
                Log.i(TAG, statusStr);
                recognizeStateHandler(statusStr);

                recordButtonChangeHandler(false, statusStr);
                cancelButtonChangeHandler(View.INVISIBLE, "");

                new Thread(new Runnable() {
                    public void run() {
                        while (!mCancelPlayInitializeTTS) {
                            String TTSStr = "正在設定歐拉蜜，請稍等";
                            mTtsPlayer.playText(mContext, TTSStr, mTtsListener, false);
                            sleep(7000);
                        }
                    }
                }).start();

                mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                        MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.INITIALIZING);
            } else if (state == OlamiSpeechRecognizer.RecognizeState.DETECT_INITIALIZED) {
                mCancelPlayInitializeTTS = true;

                statusStr += getString(R.string.KeywordDetect_INITIALIZED);
                Log.i(TAG, statusStr);
                recognizeStateHandler(statusStr);

                recordButtonChangeHandler(false, statusStr);
                cancelButtonChangeHandler(View.INVISIBLE, "");

                String TTSStr = "你好，我是你的歐拉蜜，請問我能幫你作什麼";
                mTtsPlayer.playText(mContext, TTSStr, mTtsListener, true);
            } else if (state == OlamiSpeechRecognizer.RecognizeState.WAITING_FOR_DETECT) {
                statusStr += getString(R.string.KeywordDetect_WAITING_FOR_DETECT);
                Log.i(TAG, statusStr);
                recognizeStateHandler(statusStr);

                recordButtonChangeHandler(true, statusStr);
                cancelButtonChangeHandler(View.INVISIBLE, "");

                if (!mIsPlayTTS) {
                    mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                            MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.WAITING);
                }
            } else if (state == OlamiSpeechRecognizer.RecognizeState.PROCESSING) {
                mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                        MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.PROCESSING);

                statusStr += getString(R.string.RecognizeState_PROCESSING) +"...";
                Log.i(TAG, statusStr);
                recognizeStateHandler(statusStr);

                buttonStr = getString(R.string.recordButton_stop);
                recordButtonChangeHandler(true, buttonStr);
                cancelButtonChangeHandler(View.VISIBLE, "X");

                mTtsPlayer.stop();

                if (isPlayTTS) {
                    // 播放喚醒後的TTS
                    String[] wakeup_arr = {"在", "又", "是"};
                    int random = (int) (Math.random() * 3);
                    mTtsPlayer.playText(mContext, wakeup_arr[random], mTtsListener, false);
                }
            } else if (state == OlamiSpeechRecognizer.RecognizeState.COMPLETED) {
                statusStr += getString(R.string.RecognizeState_COMPLETED);
                Log.i(TAG, statusStr);
                recognizeStateHandler(statusStr);

                buttonStr = getString(R.string.recordButton_start);
                recordButtonChangeHandler(true, buttonStr);
                cancelButtonChangeHandler(View.INVISIBLE, "");
            } else if (state == OlamiSpeechRecognizer.RecognizeState.ERROR) {
                statusStr += getString(R.string.RecognizeState_ERROR);
                Log.i(TAG, statusStr);
                recognizeStateHandler(statusStr);
                errorStateHandler(statusStr);

                buttonStr = getString(R.string.RecognizeState_ERROR);
                recordButtonChangeHandler(true, buttonStr);
                cancelButtonChangeHandler(View.INVISIBLE, "");
            }
        }

        // * Implement override method to get callback when the results
        //   of speech recognition changes.
        @Override
        public void onRecognizeResultChange(APIResponse response) {
            // * Get recognition results.
            //   In this example, we only handle the speech-to-text result.
            SpeechResult sttResult = response.getData().getSpeechResult();
            if (sttResult.complete()) {
                // 'complete() == true' means returned text is final result.
                // --------------------------------------------------
                // * It also means you can get NLI/IDS results if included.
                //   So you can handle or process NLI/IDS results here ...
                //
                //   For example:
                //   NLIResult[] nliResults = response.getData().getNLIResults();
                //
                // * See also :
                //   - OLAMI Java Client SDK & Examples
                //   - ai.olami.nli.NLIResult.
                // --------------------------------------------------
                STTChangeHandler(sttResult.getResult());
                APIResponseChangeHandler(response.toString());

                APIResponseData apiResponseData = response.getData();
                NLIResult nliResults[] = apiResponseData.getNLIResults();
                if (nliResults == null) {
                    mTtsPlayer.playText(mContext, "你一個字都沒說，你可以在說一次嗎", mTtsListener, true);
                } else {
                    for (int i = 0; i < nliResults.length; i++) {
                        if (nliResults[i].hasDataObjects()) {
                            String content = DumpIDSDataExample.dumpIDSData(nliResults[i]);
                            if (content != null) {
                                mTtsPlayer.playText(mContext, content, mTtsListener, true);
                            }
                        } else if (nliResults[i].hasDescObject()) {
                            DescObject nliDescObj = nliResults[i].getDescObject();
                            mTtsPlayer.playText(mContext, nliDescObj.getReplyAnswer(), mTtsListener, true);
                        }
                    }
                }
            } else {
                // Recognition has not yet been completed.
                // The text you get here is not a final result.
                if (sttResult.getStatus() == SpeechResult.STATUS_RECOGNIZE_OK) {
                    STTChangeHandler(sttResult.getResult());
                    APIResponseChangeHandler(response.toString());
                }

            }
        }

        public void onRecordingVolumeChange(int volumeValue) {

        }

        // * Implement override method to get callback when the volume of
        //   voice input changes.
        public void onProcessingVolumeChange(int volumeValue) {
            // Do something here when you get the changed volume.
            voiceVolumeChangeHandler(volumeValue);
        }

        // * Implement override method to get callback when server error occurs.
        @Override
        public void onServerError(APIResponse response) {
            Log.e(TAG, "Server error code: "+ response.getErrorCode()
                    +", Error message: " + response.getErrorMessage());
            errorStateHandler("onServerError Code: "+ response.getErrorCode());
        }

        // * Implement override method to get callback when error occurs.
        @Override
        public void onError(OlamiSpeechRecognizer.Error error) {
            Log.e(TAG, "Error code:"+ error.name());
            errorStateHandler("OlamiSpeechRecognizer.Error: "+ error.name());
        }

        // * Implement override method to get callback when exception occurs.
        @Override
        public void onException(Exception e) {
            e.printStackTrace();
            mTtsPlayer.playText(mContext, "歐拉蜜秀豆了，你可以在說一次嗎", mTtsListener, true);
            mRecognizer.restart();
        }
    }

    private class MyTtsListener implements ITtsListener {
        @Override
        public void onPlayingTTS() {
            mIsPlayTTS = true;
            mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                    MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.SPEAKING);
        }

        @Override
        public void onPlayEnd() {
            mIsPlayTTS = false;

            if (mEnableKeyDetect) {
                mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                        MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.WAITING);
            } else {
                mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                        MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.SLEEPING);
            }
        }

        @Override
        public void onPlayFlagEnd(String flag) {
            Log.i(TAG, "---onPlayFlagEnd()---"+ flag);
        }

        @Override
        public void onTTSPower(long power) {

        }
    }

    private class MicrophoneArrayListener implements IMicrophoneArrayControlListener {

        @Override
        public void onButtonAClick() {

            if (mRecognizer != null) {
                mRecognizeState = mRecognizer.getRecognizeState();

                // Check to see if we should start recording or stop manually.
                if (mRecognizeState == OlamiSpeechRecognizer.RecognizeState.STOPPED
                        || mRecognizeState == OlamiSpeechRecognizer.RecognizeState.WAITING_FOR_DETECT) {
                    try {
                        // * Request to start voice recording and recognition.
                        mRecognizer.OlamiIsWakeUp(true);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    recordButton.setEnabled(false);
                }
            }
        }

        @Override
        public void onButtonBClick() {
            if (mRecognizer != null) {
                mRecognizer.stop();
            }
        }

        @Override
        public void onButtonCClick() {
            if (mRecognizer != null) {
                mRecognizer.cancel();

                if (mEnableKeyDetect) {
                    mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                            MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.WAITING);
                } else {
                    mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                            MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.SLEEPING);
                }
            }
        }

        @Override
        public void onButtonDClick() {
            if (mEnableKeyDetect) {
                mEnableKeyDetect = false;
                mTtsPlayer.playText(mContext, "歐拉蜜要去睡覺了，擺擺", mTtsListener, false);
                mMicrophoneArrayLEDControlHelper.changeMicrophoneArrayLEDState(
                        MicrophoneArrayLEDControlHelper.MicrophoneArrayLEDState.SLEEPING);

                releaseRecognizer();
            } else {
                mEnableKeyDetect = true;
                init();
            }
        }
    }

    private void recordButtonChangeHandler(final boolean isEnabled, final String buttonString) {
        new Handler(this.getMainLooper()).post(new Runnable(){
            public void run(){
                recordButton.setEnabled(isEnabled);
                recordButton.setText(buttonString);
            }
        });
    }

    private void cancelButtonChangeHandler(final int isVisibility, final String buttonString) {
        new Handler(this.getMainLooper()).post(new Runnable(){
            public void run(){
                cancelButton.setVisibility(isVisibility);
                cancelButton.setText(buttonString);
            }
        });
    }

    private void voiceVolumeChangeHandler(final int volume) {
        final int volumeBarItemCount = volume / VOLUME_BAR_ITEM_VALUE;

        new Handler(this.getMainLooper()).post(new Runnable(){
            public void run(){
                voiceVolumeText.setText(getString(R.string.Volume) +" : "+ volume);
                // Voice volume bar value change
                String voiceVolumeBarStr = "▌";
                for (int i = 1; i < volumeBarItemCount && i <= VOLUME_BAR_MAX_ITEM;
                     i++) {
                    voiceVolumeBarStr += "▌";
                }
                voiceVolumeBar.setText(voiceVolumeBarStr);

                // Voice volume bar color change
                if (volumeBarItemCount >= 0 && volumeBarItemCount <= 7) {
                    voiceVolumeBar.setTextColor(Color.GREEN);
                } else if (volumeBarItemCount >= 7 && volumeBarItemCount <= 14) {
                    voiceVolumeBar.setTextColor(Color.BLUE);
                } else {
                    voiceVolumeBar.setTextColor(Color.RED);
                }
            }
        });
    }

    private void STTChangeHandler(final String STTStr) {
        new Handler(this.getMainLooper()).post(new Runnable(){
            public void run(){
                STTText.setText(STTStr);
            }
        });
    }

    private void APIResponseChangeHandler(final String APIResponseStr) {
        new Handler(this.getMainLooper()).post(new Runnable(){
            public void run(){
                APIResponseText.setText(getString(R.string.Response) +" :\n"+ APIResponseStr);
            }
        });
        Log.i(TAG, APIResponseStr);
    }

    private void recognizeStateHandler(final String recognizeStatusStr) {
        new Handler(this.getMainLooper()).post(new Runnable(){
            public void run(){
                recognizeStatusText.setText(recognizeStatusStr);
            }
        });
    }

    private void errorStateHandler(final String errorString) {
        new Handler(this.getMainLooper()).post(new Runnable(){
            public void run(){
                Toast.makeText(getApplicationContext(),
                        errorString,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
