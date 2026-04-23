package com.example.root.ffttest2;

import static com.example.root.ffttest2.Constants.tv4;

import android.app.Activity;
import android.icu.number.NumberRangeFormatter;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Random;

public class SendChirpAsyncTask extends AsyncTask<Void, Void, Void> {
    private static int globalAttemptCounter = 0; //ID
    private static int m_attempt = 0;
    Activity av;
    int num_measurements = 0;
    private java.util.List<com.example.root.ffttest2.transport.ImagePacket> packetQueue;

    public SendChirpAsyncTask(Activity activity, int num_measurements) {
        this.av = activity;
        this.num_measurements = num_measurements;
    }

    public SendChirpAsyncTask(Activity activity, java.util.List<com.example.root.ffttest2.transport.ImagePacket> packets) {
        this.av = activity;
        this.packetQueue = packets;
        this.num_measurements = 1; // One "session" for the whole image
    }

    public static String getSyncTag() {
        long bootTime = SystemClock.elapsedRealtime();
        String humanTime = new SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new Date());
        return "[" + humanTime + " | Ref:" + bootTime + "]";
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
    }

    public void setupTimer() {
        av.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                double totalTime = 0;
                if (Constants.user.equals(Constants.User.Alice)) {
                    double soundingTimeTx = 1;
                    double extractionFeedbackTime = 1;
                    totalTime += (soundingTimeTx + Constants.WaitForFeedbackTime +
                            extractionFeedbackTime);
                    if (Constants.SEND_DATA) {
                        totalTime+=Constants.WaitForDataTime;
                    }
                    Constants.AliceTime = (int)totalTime;
                    totalTime *= 1000;
                    totalTime *= num_measurements;

                    totalTime += 1000+(Constants.initSleep*1000);
                }
                else if (Constants.user.equals(Constants.User.Bob)) {
                    int extractSoundingTime = 1;
                    int sendFeedbackTime = 1;
                    totalTime += Constants.WaitForSoundingTime+
                            extractSoundingTime+sendFeedbackTime;
                    totalTime += Constants.SoundingOffset;
                    if (Constants.SEND_DATA) {
                        totalTime+=Constants.WaitForDataTime;
                    }
                    Constants.BobTime = (int)totalTime;
                    totalTime *= 1000;
                    totalTime *= num_measurements;
                    totalTime += 1000+(Constants.initSleep*1000);
                }
            }
        });
    }// --- START: Code for Automation and Logging ---

    private Handler automationHandler = new Handler();
    private boolean isAutomationRunning = false;
    private Random random = new Random();
    public static MainActivity activityInstance;
// --- END: Code for Automation and Logging ---

    @Override
    protected void onPostExecute(Void unused) {
        super.onPostExecute(unused);

        MainActivity.unreg(av);

        if (Constants.timer!=null) {
            Constants.timer.cancel();
            if (tv4 != null) tv4.setText("0");
        }

        Constants.sp1=null;
        Constants._OfflineRecorder = null;
        
        // Reset state so UI can be interacted with again
        Constants.work = false;
        Constants.toggleUI(true);
        
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("SYSTEM", "TASK_END", getSyncTag() + " AsyncTask Finished Cleanly");
        }
    }

    @Override
    protected Void doInBackground(Void... voids) {
        Constants.WaitForFeedbackTime = Constants.WaitForFeedbackTimeDefault + Constants.SyncLag;
        Constants.WaitForSoundingTime = Constants.WaitForSoundingTimeDefault + Constants.SyncLag - Constants.SoundingOffset;
        Constants.WaitForBerTime = Constants.WaitForBerTimeDefault + Constants.SyncLag;
        Constants.WaitForPerTime = Constants.WaitForPerTimeDefault + Constants.SyncLag;

        Constants.SEND_DATA=true;
        Constants.WaitForDataTime = Constants.WaitForPerTime;
        Constants.AdaptationMethod = 3;

        FileOperations.writetofile(MainActivity.av, Constants.SNR_THRESH2+"\n"+Constants.FreAdaptScaleFactor+"\n"+Constants.SNR_THRESH2_2,
                Utils.genName(Constants.SignalType.AdaptParams,0)+".txt");

        setupTimer();

        sleep(Constants.initSleep * 1000);

        Constants.StartingTimestamp = System.currentTimeMillis();
        appendToLog(Constants.SignalType.Start.toString());

        globalAttemptCounter++;
        m_attempt++;

        if (Constants.user.equals(Constants.User.Alice)) {
            FileOperations.writetofile(MainActivity.av, Constants.FLIP_SYMBOL + "",
                    Utils.genName(Constants.SignalType.FlipSyms, 0) + ".txt");
        }

        for (int i = 0; i < num_measurements; i++) {
            Log.e("timer","work "+i);
            int flag = work(i);
            updateTimer((i+1)+"");
            if (flag == -1) {
                updateTimer("-1");
                break;
            }
        }
        return null;
    }

    public static void appendToLog(String s) {
        if (s.equals(Constants.SignalType.Start.toString())) {
            if (Constants.user.equals(Constants.User.Alice)) {
                String ts = System.currentTimeMillis()+"";
                String filename = Constants.user.toString() + "-" + Constants.SignalType.Sounding + "-" + "log";
                FileOperations.appendtofile(MainActivity.av, ts + "\n", filename + ".txt");
                filename = Constants.user.toString() + "-" + Constants.SignalType.Data + "-" + "log";
                FileOperations.appendtofile(MainActivity.av, ts + "\n", filename + ".txt");
            }
            else {
                String filename = Constants.user.toString() + "-" + Constants.SignalType.Feedback + "-" + "log";
                FileOperations.appendtofile(MainActivity.av, System.currentTimeMillis() + "\n", filename + ".txt");
            }
        }
        else {
            String filename = Constants.user.toString() + "-" + s + "-" + "log";
            FileOperations.appendtofile(MainActivity.av, System.currentTimeMillis() + "\n", filename + ".txt");
        }
    }

    public void updateTimer(String ss) {
        MainActivity.av.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tv4 != null) tv4.setText(ss);
            }
        });
    }

    public int work(int m_attempt) {
        double[] tx_preamble = PreambleGen.preamble_d();
        //String signalName = (Constants.mmap != null) ? Constants.mmap.get(Constants.messageID) : "Unknown";

        if (Constants.user.equals(Constants.User.Alice)) {
            int chirpLoopNumber = 0;
            double[] feedback_signal = null;
            /*do {
                short[] sig = PreambleGen.sounding_signal_s();
                FileOperations.writetofile(MainActivity.av, sig, Utils.genName(Constants.SignalType.Sounding, m_attempt) + ".txt");

                // --- ALICE PREAMBLE LOGGING ---
                long bootTime = SystemClock.elapsedRealtime();
                String humanTs = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
                String syncTag = "[" + humanTs + " | Ref:" + bootTime + "]";

                MainActivity.activityInstance.logPerf("ALICE", "MIC_HARDWARE_STOP", syncTag + " Ready to Send");
                MainActivity.activityInstance.logPerf("ALICE", "PREAMBLE_SEND_START",
                        syncTag + " Signal:" + signalName + " ID:" + Constants.messageID + " Attempt:" + m_attempt);

                Constants.sp1 = new AudioSpeaker(av, sig, Constants.fs, 0, sig.length, false);
                appendToLog(Constants.SignalType.Sounding.toString());

                MainActivity.activityInstance.logPerf("ALICE", "SPEAKER_HARDWARE_START", syncTag + " Playing Preamble");
                Constants.sp1.play(Constants.volume);

                int sig_len = (int) (((double) sig.length / Constants.fs) * 1000);
                sleep(sig_len + Constants.SendPad);

                // --- ALICE WAIT FOR ACK ---
                MainActivity.activityInstance.logPerf("ALICE", "MIC_HARDWARE_START", syncTag + " Listening for Feedback ACK...");
                feedback_signal = Utils.waitForChirp(Constants.SignalType.Feedback, m_attempt, chirpLoopNumber);

                if (feedback_signal == null) {
                    MainActivity.activityInstance.logPerf("ALICE", "TIMEOUT_RETRY", syncTag + " Loop " + chirpLoopNumber + " failed. No ACK heard.");
                    chirpLoopNumber++;
                } else {
                    MainActivity.activityInstance.logPerf("ALICE", "ACK_RCV_SUCCESS", syncTag + " Handshake Complete");
                }

                if (chirpLoopNumber >= 3 || !Constants.work) return -1;

            } while (feedback_signal == null);*/

            // Alice logic inside work()
            // Inside the 'work' method for Alice
            do {
                short[] sig = PreambleGen.sounding_signal_s();

                // Log 1: Hardware Transition
                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("ALICE", "MIC_HARDWARE_STOP", getSyncTag() + " Ready to Send");
                }
                try {
                    Thread.sleep(10);
                } catch (Exception e) {
                } // Force clock tick

                // Log 2: Protocol Start
                String sigName = (Constants.mmap != null && Constants.mmap.containsKey(Constants.messageID))
                        ? Constants.mmap.get(Constants.messageID) : "null";
                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("ALICE", "PREAMBLE_SEND_START", getSyncTag() + " Signal:" + sigName + " ID:" + Constants.messageID);
                }
                try {
                    Thread.sleep(10);
                } catch (Exception e) {
                } // Force clock tick

                // Log 3: Speaker Start
                if (Constants.sp1 != null) Constants.sp1.release();
                Constants.sp1 = new AudioSpeaker(av, sig, Constants.fs, 0, sig.length, false);
                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("ALICE", "SPEAKER_HARDWARE_START", getSyncTag() + " Playing Preamble");
                }
                Constants.sp1.play(Constants.volume);
                int sig_len = (int) (((double) sig.length / Constants.fs) * 1000);
                sleep(sig_len + Constants.SendPad);

                // Log 4: Mic Start
                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("ALICE", "MIC_HARDWARE_START", getSyncTag() + " Listening for ACK...");
                }
                feedback_signal = Utils.waitForChirp(Constants.SignalType.Feedback, m_attempt, chirpLoopNumber);

                if (feedback_signal == null) {
                    if (MainActivity.activityInstance != null) {
                        MainActivity.activityInstance.logPerf("ALICE", "TIMEOUT_RETRY", getSyncTag() + " Attempt " + chirpLoopNumber + " Failed");
                    }
                    chirpLoopNumber++;
                } else {
                    if (MainActivity.activityInstance != null) {
                        MainActivity.activityInstance.logPerf("ALICE", "ACK_RCV_SUCCESS", getSyncTag() + " Handshake OK");
                    }
                }
            } while (feedback_signal == null && chirpLoopNumber < 3);

            // --- FUNCTIONAL LOGIC: BINS EXTRACTION ---
            double[] seg = Utils.segment(feedback_signal, 0, 24000 - 1);
            double[] xcorr_out = Utils.xcorr_online(tx_preamble, seg);
            int[] valid_bins = FeedbackSignal.extractSignalHelper(feedback_signal, (int) xcorr_out[1], m_attempt);

            if (Constants.SEND_DATA) {
                appendToLog(Constants.SignalType.Data.toString());
                if (valid_bins != null && valid_bins.length >= 1 && valid_bins[0] != -1) {
                    if (packetQueue != null && !packetQueue.isEmpty()) {
                        for (com.example.root.ffttest2.transport.ImagePacket packet : packetQueue) {
                            sendPacket(packet, valid_bins, m_attempt);
                            sleep(500); // Inter-packet gap for echoes
                        }
                    } else {
                        sendData(valid_bins, m_attempt);
                    }
                }
                try {
                    Thread.sleep(3000);
                } catch (Exception e) {
                    Log.e("asdf", e.toString());
                }
            }
            return 0;

        }  else if (Constants.user.equals(Constants.User.Bob)) {
            int chirpLoopNumber = 0;
            int[] valid_bins = null;
            double[] sounding_signal = null;

            do {
                sounding_signal = Utils.waitForChirp(Constants.SignalType.Sounding, m_attempt, chirpLoopNumber);
                if (sounding_signal == null) return -1;

                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("BOB", "PREAMBLE_RCV_START", getSyncTag() + " Triggered!");
                }

                double[] seg = Utils.segment(sounding_signal, 0, 24000 - 1);
                double[] xcorr_out = Utils.xcorr_online(tx_preamble, seg);

                // SNR Robust Fix (Preventing NaN)
                double signalPower = xcorr_out[0];
                double noisePower = 0.01 + 0.0001;
                double snrVal = 10 * Math.log10(signalPower / noisePower);
                if (Double.isNaN(snrVal)) snrVal = 0.0;

                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("BOB", "PREAMBLE_RCV_END", getSyncTag() + " SNR:" + String.format("%.2f", snrVal) + "dB | Peak:" + String.format("%.2f", signalPower));
                }

                valid_bins = ChannelEstimate.extractSignal_withsymbol_helper(av, sounding_signal, (int) xcorr_out[1], m_attempt);
                chirpLoopNumber++;

                if (!Constants.work) return -1;
            } while (valid_bins == null || valid_bins.length == 0 || valid_bins[0] == -1);

            // --- BOB SEND ACK WITH TIMESTAMPS ---
            short[] feedback = FeedbackSignal.encodeFeedbackSignal(valid_bins[0], valid_bins[valid_bins.length - 1],
                    Constants.fbackTime, true, m_attempt);

            if (Constants.sp1 != null) Constants.sp1.release();
            Constants.sp1 = new AudioSpeaker(av, feedback, Constants.fs, 0, feedback.length, false);

            if (MainActivity.activityInstance != null) {
                MainActivity.activityInstance.logPerf("BOB", "MIC_HARDWARE_STOP", getSyncTag() + " Sending ACK");

                // FIXED: Added bobAckSync to ACK_SEND_START
                MainActivity.activityInstance.logPerf("BOB", "ACK_SEND_START", getSyncTag() + " ID:HANDSHAKE_ACK");
            }

            Constants.sp1.play(Constants.volume);

            if (MainActivity.activityInstance != null) {
                // FIXED: Added bobAckSync to SPEAKER_HARDWARE_STOP
                MainActivity.activityInstance.logPerf("BOB", "SPEAKER_HARDWARE_STOP", getSyncTag() + " ACK Playback Finished");
            }

            int stime = (int) ((feedback.length / (double) Constants.fs) * 1000);
            // Increased to 1.5s to ensure Bob's hardware is stable and Alice is ready
            sleep(stime + 1500); 

            if (Constants.SEND_DATA) {
                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("BOB", "MIC_HARDWARE_START", getSyncTag() + " Listening for Data Payload...");
                }
                boolean isImagePacket;
                do {
                    double[] data_signal = Utils.waitForChirp(Constants.SignalType.DataRx, m_attempt, 0);
                    if (data_signal != null) {
                        isImagePacket = Decoder.decode_helper(av, data_signal, valid_bins);
                    } else {
                        break; // Timeout or stopped
                    }
                } while (isImagePacket && Constants.work);
            }
            return 0;
        }
        return 0;
    }


    public static void sendPacket(com.example.root.ffttest2.transport.ImagePacket packet, int[] valid_bins, int m_attempt) {
        // Give Bob 1.5s to start recording and hardware to stabilize
        sleep(1500); 

        short[] bits = SymbolGeneration.getPacketBits(packet);
        short[] txsig = SymbolGeneration.generateDataSymbols(bits, valid_bins, Constants.data_symreps, true, Constants.SignalType.DataAdapt, m_attempt);
        
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("ALICE", "DATA_SEND_START", getSyncTag() + " Pkt:" + packet.packetIndex + "/" + packet.totalPackets);
        }
        if (Constants.sp1 != null) Constants.sp1.release();
        Constants.sp1 = new AudioSpeaker(MainActivity.av, txsig, Constants.fs, 0, txsig.length, false);
        Constants.sp1.play(Constants.volume);
        
        int duration = (int) (((double) txsig.length / Constants.fs) * 1000);
        sleep(duration + Constants.SendPad);
    }

    public static void sendData(int[] valid_bins, int m_attempt) {
        send_data_per(valid_bins,m_attempt);
    }

    public static void send_data_helper(int numbits, int[] valid_bins, int m_attempt,
                                        Constants.SignalType sigType, Constants.ExpType expType) {
        short[] bits = SymbolGeneration.getCodedBits();

        // Convert bits to a single String for one CSV column
        StringBuilder bitStr = new StringBuilder();
        for (short b : bits) bitStr.append(b);
        String bitSequence = bitStr.toString();

        short[] txsig = SymbolGeneration.generateDataSymbols(bits, valid_bins, Constants.data_symreps, true, sigType, m_attempt);

        double duration = (double) txsig.length / Constants.fs;
        double bitrate = (double) bits.length / duration;

        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("ALICE", "DATA_SEND_START", getSyncTag() + " ID:" + Constants.messageID);
            MainActivity.activityInstance.logPerf("ALICE", "DATA_INFO", getSyncTag() + " Bits:" + bitSequence + " | Count:" + bits.length + " | Bitrate:" + String.format("%.2f", bitrate) + "bps");
        }

        // Give Bob 1.5s to start recording and hardware to stabilize
        sleep(1500); 

        if (Constants.sp1 != null) Constants.sp1.release();
        Constants.sp1 = new AudioSpeaker(MainActivity.av, txsig, Constants.fs, 0, txsig.length, false);
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("ALICE", "SPEAKER_HARDWARE_START", getSyncTag() + " Playing Data");
        }
        Constants.sp1.play(Constants.volume);
        //MainActivity.activityInstance.logPerf("ALICE", "SPEAKER_HARDWARE_STOP", "Transmission Complete");
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("ALICE", "SPEAKER_HARDWARE_STOP", getSyncTag() + " Transmission Complete");
        }
    }


    public static void send_data_ber(int[] valid_bins, int m_attempt) {
        FileOperations.writetofile(MainActivity.av, Constants.codeRate.toString(),
                Utils.genName(Constants.SignalType.CodeRate,m_attempt)+".txt");
        FileOperations.writetofile(MainActivity.av, Utils.trim(Arrays.toString(valid_bins)),
                Utils.genName(Constants.SignalType.ValidBins, m_attempt) + ".txt");

        // adaptive  //////////////////////////////////////////////
        send_data_helper(valid_bins.length*Constants.Nsyms,
                valid_bins, m_attempt,
                Constants.SignalType.DataAdapt,
                Constants.ExpType.BER);
        // full bandwidth//////////////////////////////////////////////
        int[] end_bins = new int[]{79,49,29};
        Constants.SignalType[] sigTypes = new Constants.SignalType[]{
                Constants.SignalType.DataFull_1000_4000,
                Constants.SignalType.DataFull_1000_2500,
                Constants.SignalType.DataFull_1000_1500,
        };
        for (int i = 0; i < end_bins.length; i++) {
            int[] bins = generateBins(20, end_bins[i]);
            send_data_helper(bins.length * Constants.Nsyms, bins, m_attempt,
                    sigTypes[i],Constants.ExpType.BER);
        }
        //////////////////////////////////////////////
    }

    public static int[] generateBins(int bin1, int bin2) {
        int[] bins = new int[bin2-bin1+1];
        int counter=0;
        for (int i = bin1; i <= bin2; i++) {
            bins[counter++]=i;
        }
        return bins;
    }

    public static void send_data_per(int[] valid_bins, int m_attempt) {
        FileOperations.writetofile(MainActivity.av, Constants.codeRate.toString(),
                Utils.genName(Constants.SignalType.CodeRate,m_attempt)+".txt");
        FileOperations.writetofile(MainActivity.av, Utils.trim(Arrays.toString(valid_bins)),
                Utils.genName(Constants.SignalType.ValidBins, m_attempt) + ".txt");

        // calc bits//////////////////////////////////////////////
        int msgbits = 16;
        int traceDepth = 0;
        msgbits += traceDepth;

        // adapt//////////////////////////////////////////////
        send_data_helper(msgbits,
                valid_bins, m_attempt,
                Constants.SignalType.DataAdapt, Constants.ExpType.PER);
        Log.e("numbits","adapt "+msgbits);
        // full bandwidth//////////////////////////////////////////////
    }

    public static void sleep(int s) {
        try {
            Thread.sleep(s);
        }
        catch (Exception e) {
            Utils.log(e.getMessage());
        }
    }
}
