package com.example.root.ffttest2;

import android.app.Activity;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;


public class Decoder {
    private static com.example.root.ffttest2.transport.ImageAssembler imageAssembler;

    public static void setImageAssembler(com.example.root.ffttest2.transport.ImageAssembler assembler) {
        imageAssembler = assembler;
    }

    /**
     * Decodes the received data and returns true if it was a valid image packet.
     */
    public static boolean decode_helper(Activity av, double[] data, int[] valid_bins) {
        // --- 1. DATA_RCV_START & HARDWARE TRIGGER ---
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "DATA_RCV_START", SendChirpAsyncTask.getSyncTag() + " Beginning Decoding");
            // Log that hardware recording has stopped and software processing has begun
            MainActivity.activityInstance.logPerf("BOB", "MIC_HARDWARE_STOP", SendChirpAsyncTask.getSyncTag() + " Buffer Full -> Processing Start");
        }

        // --- EXISTING FUNCTIONAL LOGIC (DO NOT REMOVE) ---
        long tStart = SystemClock.elapsedRealtime();
        data = Utils.filter(data);
        long tFiltered = SystemClock.elapsedRealtime();
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "Filter:" + (tFiltered - tStart) + "ms");
        }

        // Fixed: Use local variables to avoid modifying the original valid_bins array
        int bin1 = valid_bins[0] + Constants.nbin1_default;
        int bin2 = valid_bins[1] + Constants.nbin1_default;

        // Image packets are typically 64 bytes (512 bits) + 9 byte header = 584 bits.
        // We need to determine the fill order based on the actual expected bit count.
        int expectedBits = 600; // Safe upper bound for a 64-byte packet + header
        int[] binFillOrder = SymbolGeneration.binFillOrder(Utils.arange(bin1, bin2), expectedBits);

        int ptime = (int) ((Constants.preambleTime / 1000.0) * Constants.fs);
        int start = ptime + Constants.ChirpGap;

        double[] rx_pilots = Utils.segment(data, start + Constants.Cp, start + Constants.Cp + Constants.Ns - 1);
        start = start + Constants.Cp + Constants.Ns;

        double[] tx_pilots = Utils.convert(SymbolGeneration.getTrainingSymbol(Utils.arange(bin1, bin2)));
        tx_pilots = Utils.segment(tx_pilots, Constants.Cp, Constants.Ns + Constants.Cp - 1);

        double[][] tx_spec = Utils.fftcomplexoutnative_double(tx_pilots, tx_pilots.length);
        double[][] rx_spec = Utils.fftcomplexoutnative_double(rx_pilots, rx_pilots.length);
        double[][] weights = Utils.dividenative(tx_spec, rx_spec);
        double[][] recovered_pilot_sym = Utils.timesnative(rx_spec, weights);
        long tChanEst = SystemClock.elapsedRealtime();
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "ChanEst:" + (tChanEst - tFiltered) + "ms");
        }

        int numsyms = binFillOrder[0];
        double[][][] symbols = new double[numsyms + 1][][];
        symbols[0] = recovered_pilot_sym;

        long tFFTStart = SystemClock.elapsedRealtime();
        for (int i = 0; i < numsyms; i++) {
            int symStart = start + Constants.Cp;
            int symEnd = start + Constants.Cp + Constants.Ns - 1;
            
            // BOUNDS CHECK: Ensure we have enough data for the next symbol
            if (symEnd >= data.length) {
                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("BOB", "DECODE_ERROR", "Signal Truncated at symbol " + i + " (DataLen:" + data.length + ")");
                }
                break;
            }

            double[] sym = Utils.segment(data, symStart, symEnd);
            start = start + Constants.Cp + Constants.Ns;

            double[][] sym_spec = Utils.fftcomplexoutnative_double(sym, sym.length);
            sym_spec = Utils.timesnative(sym_spec, weights);
            symbols[i + 1] = sym_spec;
        }
        long tFFTEnd = SystemClock.elapsedRealtime();
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "FFT_Total:" + (tFFTEnd - tFFTStart) + "ms | Syms:" + numsyms);
        }

        short[][] bits = Modulation.pskdemod_differential(symbols, new int[]{bin1 - Constants.nbin1_default, bin2 - Constants.nbin1_default});
        long tDemod = SystemClock.elapsedRealtime();
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "Demod:" + (tDemod - tFFTEnd) + "ms");
        }

        StringBuilder codedBuilder = new StringBuilder();
        for (int i = 0; i < bits.length; i++) {
            short[] newbits = SymbolGeneration.unshuffle(bits[i], i);
            for (int j = 0; j < binFillOrder[i + 1]; j++) {
                codedBuilder.append(newbits[j]);
            }
        }
        String coded = codedBuilder.toString();

        long tViterbiStart = SystemClock.elapsedRealtime();
        String uncoded = Utils.decode(coded, Constants.cc[0], Constants.cc[1], Constants.cc[2]);
        long tViterbiEnd = SystemClock.elapsedRealtime();
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "Viterbi:" + (tViterbiEnd - tViterbiStart) + "ms");
        }
        // --- END EXISTING FUNCTIONAL LOGIC ---

        // --- IMAGE PACKET DETECTION ---
        try {
            byte[] decodedBytes = bitsToBytes(coded);
            com.example.root.ffttest2.transport.ImagePacket packet = com.example.root.ffttest2.transport.ImagePacket.fromBytes(decodedBytes);

            if (packet != null && imageAssembler != null) {
                if (MainActivity.activityInstance != null) {
                    MainActivity.activityInstance.logPerf("BOB", "IMAGE_PKT_RCV", SendChirpAsyncTask.getSyncTag() + " Pkt:" + packet.packetIndex + "/" + packet.totalPackets);
                }
                imageAssembler.addPacket(packet);
                return true; // Handled as image packet
            }
        } catch (Exception e) {
            // Not an image packet or malformed
        }

        // --- 2. BIT LOSS & ID LOGGING ---

        int messageID = -1;
        try { messageID = Integer.parseInt(uncoded, 2); } catch (Exception e) {}
        String sigName = (Constants.mmap != null && Constants.mmap.containsKey(messageID)) ? Constants.mmap.get(messageID) : "Unknown";

        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "RAW_CODED_BITS", SendChirpAsyncTask.getSyncTag() + " Bits:" + coded + " | Len:" + coded.length());
            MainActivity.activityInstance.logPerf("BOB", "DATA_RCV_END", SendChirpAsyncTask.getSyncTag() + " Decoded ID:" + messageID + " (" + sigName + ")");
            MainActivity.activityInstance.logPerf("BOB", "DATA_INFO", SendChirpAsyncTask.getSyncTag() + " Rec_Bits:" + uncoded + " | Rec_Count:" + uncoded.length());
        }

        Utils.log(coded + " => " + uncoded + " => " + sigName);
        return false;
    }

    private static byte[] bitsToBytes(String bits) {
        if (bits == null || bits.length() < 8) return new byte[0];
        byte[] bytes = new byte[bits.length() / 8];
        for (int i = 0; i < bytes.length; i++) {
            try {
                String byteStr = bits.substring(i * 8, i * 8 + (8));
                bytes[i] = (byte) Integer.parseInt(byteStr, 2);
            } catch (Exception e) {
                bytes[i] = 0;
            }
        }
        return bytes;
    }
}

