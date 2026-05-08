package com.example.root.ffttest2;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;


public class Decoder {
    private static com.example.root.ffttest2.transport.ImageAssembler imageAssembler;

    public static void setImageAssembler(com.example.root.ffttest2.transport.ImageAssembler assembler) {
        imageAssembler = assembler;
    }

    public static com.example.root.ffttest2.transport.ImageAssembler getImageAssembler() {
        return imageAssembler;
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

        // Fixed: Use absolute bin numbers directly if provided
        int bin1 = (valid_bins != null && valid_bins.length >= 1 && valid_bins[0] != -1) ? 
                   valid_bins[0] : 20;
        int bin2 = (valid_bins != null && valid_bins.length >= 1 && valid_bins[valid_bins.length - 1] != -1) ? 
                   valid_bins[valid_bins.length - 1] : 79;

        // Image packets are typically 128 bytes (1024 bits) + 9 byte header = 1096 bits.
        // The Viterbi encoder adds (K-1) flushing bits. 
        int uncodedBits = 1096;
        int expectedBits = (uncodedBits + Constants.cc[2] - 1) * 2;
        int[] binFillOrder = SymbolGeneration.binFillOrder(Utils.arange(bin1, bin2), expectedBits);
        Log.d("BOB", "Expected Bits: " + expectedBits + " | Symbols needed: " + binFillOrder[0] + " | Bins: [" + bin1 + ", " + bin2 + "]");

        int ptime = PreambleGen.preamble_s().length;
        int start = ptime + Constants.ChirpGap;

        // SAFETY CHECK: Ensure we have enough data for pilots
        if (data == null || start + Constants.Cp + Constants.Ns >= data.length) {
            Log.e("Decoder", "Signal too short for pilots (DataLen: " + (data != null ? data.length : 0) + ")");
            return false;
        }

        double[] rx_pilots = Utils.segment(data, start + Constants.Cp, start + Constants.Cp + Constants.Ns - 1);
        start = start + Constants.Cp + Constants.Ns;

        double[] tx_pilots = Utils.convert(SymbolGeneration.getTrainingSymbol(Utils.arange(bin1, bin2)));
        if (tx_pilots == null || tx_pilots.length < Constants.Cp + Constants.Ns) {
             Log.e("Decoder", "Failed to generate training symbol");
             return false;
        }
        tx_pilots = Utils.segment(tx_pilots, Constants.Cp, Constants.Ns + Constants.Cp - 1);

        double[][] tx_spec = Utils.fftcomplexoutnative_double(tx_pilots, tx_pilots.length);
        double[][] rx_spec = Utils.fftcomplexoutnative_double(rx_pilots, rx_pilots.length);
        
        if (tx_spec == null || rx_spec == null) {
            Log.e("Decoder", "FFT failed for pilots");
            return false;
        }
        
        double[][] weights = Utils.dividenative(tx_spec, rx_spec);
        if (weights == null) {
            Log.e("Decoder", "Channel weight calculation failed");
            return false;
        }
        
        double[][] recovered_pilot_sym = Utils.timesnative(rx_spec, weights);
        if (recovered_pilot_sym == null) {
            Log.e("Decoder", "Pilot recovery failed");
            return false;
        }
        long tChanEst = SystemClock.elapsedRealtime();
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "ChanEst:" + (tChanEst - tFiltered) + "ms");
        }

        int numsyms = binFillOrder[0];
        double[][][] symbols = new double[numsyms + 1][][];
        symbols[0] = recovered_pilot_sym;

        int actualFilled = 0;
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
            actualFilled++;
        }
        long tFFTEnd = SystemClock.elapsedRealtime();
        
        // CRITICAL FIX: Trim symbols array to the actual number of symbols found
        if (actualFilled < numsyms) {
            symbols = Arrays.copyOf(symbols, actualFilled + 1);
        }

        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "FFT_Total:" + (tFFTEnd - tFFTStart) + "ms | Syms:" + actualFilled);
        }

        short[][] demodulatedBits = Modulation.pskdemod_differential(symbols, new int[]{bin1, bin2});
        long tDemod = SystemClock.elapsedRealtime();
        if (MainActivity.activityInstance != null) {
            MainActivity.activityInstance.logPerf("BOB", "PROC_LATENCY", "Demod:" + (tDemod - tFFTEnd) + "ms");
        }

        StringBuilder codedBuilder = new StringBuilder();
        for (int i = 0; i < demodulatedBits.length; i++) {
            short[] newbits = SymbolGeneration.unshuffle(demodulatedBits[i], i);
            // Only take the number of bits that Alice actually put in this symbol
            int bitsInThisSymbol = (i < actualFilled) ? binFillOrder[i + 1] : 0;
            for (int j = 0; j < bitsInThisSymbol; j++) {
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
        Log.d("Decoder", "Uncoded bits length: " + uncoded.length() + " | Bits: " + (uncoded.length() > 64 ? uncoded.substring(0, 64) + "..." : uncoded));
        // --- END EXISTING FUNCTIONAL LOGIC ---

        // --- IMAGE PACKET DETECTION ---
        try {
            byte[] decodedBytes = bitsToBytes(uncoded); // Use uncoded bits for image packet
            Log.d("Decoder", "Decoded bytes length: " + decodedBytes.length);
            com.example.root.ffttest2.transport.ImagePacket packet = com.example.root.ffttest2.transport.ImagePacket.fromBytes(decodedBytes);

            if (packet != null) {
                Log.d("BOB", "VALID Image Packet detected! Pkt: " + packet.packetIndex + "/" + packet.totalPackets + " | ID: " + packet.imageId);
                if (imageAssembler != null) {
                    if (MainActivity.activityInstance != null) {
                        MainActivity.activityInstance.logPerf("BOB", "IMAGE_PKT_RCV", SendChirpAsyncTask.getSyncTag() + " Pkt:" + packet.packetIndex + "/" + packet.totalPackets);
                    }
                    imageAssembler.addPacket(packet);
                    return true; // Handled as image packet
                } else {
                    Log.e("Decoder", "ImageAssembler is NULL");
                }
            } else {
                Log.w("Decoder", "Packet deserialization returned NULL (Magic mismatch?)");
            }
        } catch (Exception e) {
            Log.e("Decoder", "Exception during image packet detection: " + e.getMessage());
        }

        // --- 2. BIT LOSS & ID LOGGING ---

        int messageID = -1;
        String sigName = "Unknown";
        if (uncoded.length() <= 32) {
            try {
                messageID = Integer.parseInt(uncoded, 2);
                sigName = (Constants.mmap != null && Constants.mmap.containsKey(messageID)) ? Constants.mmap.get(messageID) : "Unknown";
            } catch (Exception e) {
            }
        } else {
            sigName = "Data Payload (" + uncoded.length() + " bits)";
        }

        if (MainActivity.activityInstance != null) {
            String displayBits = coded.length() > 64 ? coded.substring(0, 61) + "..." : coded;
            MainActivity.activityInstance.logPerf("BOB", "RAW_CODED_BITS", SendChirpAsyncTask.getSyncTag() + " Bits:" + displayBits + " | Len:" + coded.length());
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

