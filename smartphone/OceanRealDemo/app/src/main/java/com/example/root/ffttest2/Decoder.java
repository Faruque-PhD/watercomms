package com.example.root.ffttest2;

import android.app.Activity;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;


/*public class Decoder {
    public static void decode_helper(Activity av, double[] data, int[] valid_bins) {
        // --- 1. DATA_RCV_START & HARDWARE TRIGGER ---

        if (MainActivity.activityInstance != null) {
            // Log that hardware recording has stopped and software processing has begun
            MainActivity.activityInstance.logPerf("BOB", "MIC_HARDWARE_STOP", SendChirpAsyncTask.getSyncTag() + " Buffer Full -> Processing Start");
            MainActivity.activityInstance.logPerf("BOB", "DATA_RCV_START", SendChirpAsyncTask.getSyncTag() + " Starting Decoding...");
        }

        // --- EXISTING FUNCTIONAL LOGIC (DO NOT REMOVE) ---
        data = Utils.filter(data);

        valid_bins[0] = valid_bins[0] + Constants.nbin1_default;
        valid_bins[1] = valid_bins[1] + Constants.nbin1_default;

        int[] binFillOrder = SymbolGeneration.binFillOrder(Utils.arange(valid_bins[0], valid_bins[1]));

        int ptime = (int) ((Constants.preambleTime / 1000.0) * Constants.fs);
        int start = ptime + Constants.ChirpGap;

        double[] rx_pilots = Utils.segment(data, start + Constants.Cp, start + Constants.Cp + Constants.Ns - 1);
        start = start + Constants.Cp + Constants.Ns;

        double[] tx_pilots = Utils.convert(SymbolGeneration.getTrainingSymbol(Utils.arange(valid_bins[0], valid_bins[1])));
        tx_pilots = Utils.segment(tx_pilots, Constants.Cp, Constants.Cp + Constants.Ns - 1);

        double[][] tx_spec = Utils.fftcomplexoutnative_double(tx_pilots, tx_pilots.length);
        double[][] rx_spec = Utils.fftcomplexoutnative_double(rx_pilots, rx_pilots.length);
        double[][] weights = Utils.dividenative(tx_spec, rx_spec);
        double[][] recovered_pilot_sym = Utils.timesnative(rx_spec, weights);

        int numsyms = binFillOrder[0];
        double[][][] symbols = new double[numsyms + 1][][];
        symbols[0] = recovered_pilot_sym;

        for (int i = 0; i < numsyms; i++) {
            double[] sym = Utils.segment(data, start + Constants.Cp, start + Constants.Cp + Constants.Ns - 1);
            start = start + Constants.Cp + Constants.Ns;

            double[][] sym_spec = Utils.fftcomplexoutnative_double(sym, sym.length);
            sym_spec = Utils.timesnative(sym_spec, weights);
            symbols[i + 1] = sym_spec;
        }

        short[][] bits = Modulation.pskdemod_differential(symbols, valid_bins);

        String coded = "";
        for (int i = 0; i < bits.length; i++) {
            short[] newbits = SymbolGeneration.unshuffle(bits[i], i);
            for (int j = 0; j < binFillOrder[i + 1]; j++) {
                coded += newbits[j] + "";
            }
        }

        String uncoded = Utils.decode(coded, Constants.cc[0], Constants.cc[1], Constants.cc[2]);


        // After uncoded = Utils.decode(...)

// 1. LOG RAW BITS (The 28 bits Alice sent)
        MainActivity.activityInstance.logPerf("BOB", "RAW_CODED_BITS", SendChirpAsyncTask.getSyncTag() + " Bits:" + coded + " | Len:" + coded.length());

// 2. LOG DECODED BITS (The final result)
        int messageID = -1;
        try {
            messageID = Integer.parseInt(uncoded, 2);
        } catch (Exception e) {
        }
        String signalName = (Constants.mmap != null) ? Constants.mmap.get(messageID) : "Unknown";

        MainActivity.activityInstance.logPerf("BOB", "DATA_RCV_END", SendChirpAsyncTask.getSyncTag() + " Decoded ID:" + messageID + " (" + signalName + ")");
        MainActivity.activityInstance.logPerf("BOB", "DATA_INFO", SendChirpAsyncTask.getSyncTag() + " Rec_Bits:" + uncoded + " | Rec_Count:" + uncoded.length());

// 3. HARDWARE ACK LOGS
        MainActivity.activityInstance.logPerf("BOB", "SPEAKER_HARDWARE_START", SendChirpAsyncTask.getSyncTag() + " Playing ACK");

// Bob Speaker Stop with proper timestamp
        try {
            Thread.sleep(500);
        } catch (Exception e) {
        } // Wait for audio to actually play
        MainActivity.activityInstance.logPerf("BOB", "SPEAKER_HARDWARE_STOP", SendChirpAsyncTask.getSyncTag() + " ACK Finished");


        Utils.log(coded + " => " + uncoded + " => " + signalName);
    }


}*/

public class Decoder {
    public static void decode_helper(Activity av, double[] data, int[] valid_bins) {
        // --- 1. DATA_RCV_START & HARDWARE TRIGGER ---
        // Capture start time for synchronization with Alice
        MainActivity.activityInstance.logPerf("BOB", "DATA_RCV_START", SendChirpAsyncTask.getSyncTag() + " Beginning Decoding");


        if (MainActivity.activityInstance != null) {
            // Log that hardware recording has stopped and software processing has begun
            MainActivity.activityInstance.logPerf("BOB", "MIC_HARDWARE_STOP", SendChirpAsyncTask.getSyncTag() + " Buffer Full -> Processing Start");
            MainActivity.activityInstance.logPerf("BOB", "DATA_RCV_START", SendChirpAsyncTask.getSyncTag() + " Starting Decoding...");
        }

        // --- EXISTING FUNCTIONAL LOGIC (DO NOT REMOVE) ---
        long tStart = SystemClock.elapsedRealtime();
        data = Utils.filter(data);
        long tFiltered = SystemClock.elapsedRealtime();
        MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "Filter:" + (tFiltered - tStart) + "ms");

        valid_bins[0] = valid_bins[0] + Constants.nbin1_default;
        valid_bins[1] = valid_bins[1] + Constants.nbin1_default;

        int[] binFillOrder = SymbolGeneration.binFillOrder(Utils.arange(valid_bins[0], valid_bins[1]));

        int ptime = (int) ((Constants.preambleTime / 1000.0) * Constants.fs);
        int start = ptime + Constants.ChirpGap;

        double[] rx_pilots = Utils.segment(data, start + Constants.Cp, start + Constants.Cp + Constants.Ns - 1);
        start = start + Constants.Cp + Constants.Ns;

        double[] tx_pilots = Utils.convert(SymbolGeneration.getTrainingSymbol(Utils.arange(valid_bins[0], valid_bins[1])));
        tx_pilots = Utils.segment(tx_pilots, Constants.Cp, Constants.Cp + Constants.Ns - 1);

        double[][] tx_spec = Utils.fftcomplexoutnative_double(tx_pilots, tx_pilots.length);
        double[][] rx_spec = Utils.fftcomplexoutnative_double(rx_pilots, rx_pilots.length);
        double[][] weights = Utils.dividenative(tx_spec, rx_spec);
        double[][] recovered_pilot_sym = Utils.timesnative(rx_spec, weights);
        long tChanEst = SystemClock.elapsedRealtime();
        MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "ChanEst:" + (tChanEst - tFiltered) + "ms");

        int numsyms = binFillOrder[0];
        double[][][] symbols = new double[numsyms + 1][][];
        symbols[0] = recovered_pilot_sym;

        long tFFTStart = SystemClock.elapsedRealtime();
        for (int i = 0; i < numsyms; i++) {
            double[] sym = Utils.segment(data, start + Constants.Cp, start + Constants.Cp + Constants.Ns - 1);
            start = start + Constants.Cp + Constants.Ns;

            double[][] sym_spec = Utils.fftcomplexoutnative_double(sym, sym.length);
            sym_spec = Utils.timesnative(sym_spec, weights);
            symbols[i + 1] = sym_spec;
        }
        long tFFTEnd = SystemClock.elapsedRealtime();
        MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "FFT_Total:" + (tFFTEnd - tFFTStart) + "ms | Syms:" + numsyms);

        short[][] bits = Modulation.pskdemod_differential(symbols, valid_bins);
        long tDemod = SystemClock.elapsedRealtime();
        MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "Demod:" + (tDemod - tFFTEnd) + "ms");

        String coded = "";
        for (int i = 0; i < bits.length; i++) {
            short[] newbits = SymbolGeneration.unshuffle(bits[i], i);
            for (int j = 0; j < binFillOrder[i + 1]; j++) {
                coded += newbits[j] + "";
            }
        }

        long tViterbiStart = SystemClock.elapsedRealtime();
        String uncoded = Utils.decode(coded, Constants.cc[0], Constants.cc[1], Constants.cc[2]);
        long tViterbiEnd = SystemClock.elapsedRealtime();
        MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "Viterbi:" + (tViterbiEnd - tViterbiStart) + "ms");
        // --- END EXISTING FUNCTIONAL LOGIC ---

        // --- 2. BIT LOSS & ID LOGGING ---

        int messageID = -1;
        try { messageID = Integer.parseInt(uncoded, 2); } catch (Exception e) {}
        String sigName = (Constants.mmap != null && Constants.mmap.containsKey(messageID)) ? Constants.mmap.get(messageID) : "Unknown";

        MainActivity.activityInstance.logPerf("BOB", "RAW_CODED_BITS", SendChirpAsyncTask.getSyncTag() + " Bits:" + coded + " | Len:" + coded.length());
        MainActivity.activityInstance.logPerf("BOB", "DATA_RCV_END", SendChirpAsyncTask.getSyncTag() + " Decoded ID:" + messageID + " (" + sigName + ")");
        MainActivity.activityInstance.logPerf("BOB", "DATA_INFO", SendChirpAsyncTask.getSyncTag() + " Rec_Bits:" + uncoded + " | Rec_Count:" + uncoded.length());

        // 3. Hardware ACK logs with full Ref tags
        MainActivity.activityInstance.logPerf("BOB", "ACK_SEND_START", SendChirpAsyncTask.getSyncTag() + " ID:HANDSHAKE_ACK");
        MainActivity.activityInstance.logPerf("BOB", "SPEAKER_HARDWARE_START", SendChirpAsyncTask.getSyncTag() + " Playing Feedback ACK");

        // Separation sleep to ensure the Speaker Stop log gets a unique Ref
        try { Thread.sleep(300); } catch (Exception e) {}

        MainActivity.activityInstance.logPerf("BOB", "SPEAKER_HARDWARE_STOP", SendChirpAsyncTask.getSyncTag() + " ACK Playback Finished");

        Utils.log(coded + " => " + uncoded + " => " + sigName);
        }
    }

