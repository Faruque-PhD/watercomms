package com.example.root.ffttest2;

import android.util.Log;

public class Modulation {
    public static short[] pskdemod_test(double[][] fft_spectrum, int[] valid_carrier) {
        short[] out = new short[valid_carrier.length];
        for (int i = 0; i < out.length; i++) {
            out[i]=1;
        }
        return out;
    }

    /**
     * the demodulation part **/
    public static double[][] pskmod(short[] bits) {
        int bit_num = bits.length;
        double[][] mod_dat = new double[2][bit_num];
        for(int i = 0; i < bit_num; ++i){
            if(bits[i] == 0) {
                mod_dat[0][i] = 1; // real
                mod_dat[1][i] = 0; // real
            }
            else {
                mod_dat[0][i] = -1; // real
                mod_dat[1][i] = 0; // real
            }
        }
        return mod_dat;
    }

    public static short[] pskdemod(double[][] fft_spectrum, int [] valid_subcarrier) {
        if (fft_spectrum[0].length == (Constants.subcarrier_number_chanest)) {
            int[] valid_subcarrer2 = new int[valid_subcarrier.length];
            for (int i = 0; i < valid_subcarrer2.length; i++) {
                valid_subcarrer2[i] = valid_subcarrier[i]-Constants.nbin1_chanest;
            }
            return pskdemod_helper(fft_spectrum, valid_subcarrer2);
        }
        else {
            return pskdemod_helper(fft_spectrum, valid_subcarrier);
        }
    }

    public static double[] phase(double[][] vals) {
        double[] out = new double[vals[0].length];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.atan2(vals[1][i], vals[0][i]);
        }
        return out;
    }

    // number of symbols / real/imaginary / data bits
    public static short[][] pskdemod_differential(double[][][] symbols, int[] valid_bins) {
        int numbins = valid_bins[1]-valid_bins[0]+1;
        int dataBins = numbins;
        if (Constants.USE_PILOTS) {
            int pilots = (int) Math.ceil((double) numbins / Constants.PILOT_SPACING);
            dataBins = numbins - pilots;
        }
        // num symbols / num bins
        short[][] bits = new short[symbols.length-1][dataBins];
        for (int i = 0; i < symbols.length-1; i++) {
            double[][] symbol1 = symbols[i+1];
            double[][] symbol2 = symbols[i];
            
            // SAFETY: Ensure symbols are not null before calling JNI
            if (symbol1 == null || symbol2 == null) {
                Log.e("Modulation", "NULL symbol at index " + i + " or " + (i+1));
                continue;
            }

            double[][] divval = Utils.dividenative(symbol1,symbol2);
            if (divval == null) {
                Log.e("Modulation", "dividenative returned NULL for index " + i);
                continue;
            }
            
            double[] phase = phase(divval);

            // --- CONTINUOUS PHASE TRACKING (CPE Correction) ---
            if (Constants.USE_PILOTS) {
                double totalPhaseErr = 0;
                int pilotCount = 0;
                for (int j = 0; j < numbins; j++) {
                    if (j % Constants.PILOT_SPACING == 0) {
                        totalPhaseErr += phase[valid_bins[0] + j];
                        pilotCount++;
                    }
                }
                if (pilotCount > 0) {
                    double avgPhaseErr = totalPhaseErr / pilotCount;
                    for (int j = valid_bins[0]; j <= valid_bins[1]; j++) {
                        phase[j] -= avgPhaseErr;
                        // Wrap phase to [-PI, PI]
                        while (phase[j] > Math.PI) phase[j] -= 2 * Math.PI;
                        while (phase[j] < -Math.PI) phase[j] += 2 * Math.PI;
                    }
                }
            }

            int counter = 0;
            for (int j = valid_bins[0]; j <= valid_bins[1]; j++) {
                // Skip pilot bins when extracting bits
                if (Constants.USE_PILOTS && ((j - valid_bins[0]) % Constants.PILOT_SPACING == 0)) {
                    continue;
                }
                
                boolean b1 = phase[j] >= Math.PI/2;
                boolean b2 = phase[j] <= -Math.PI/2;
                if (b1|b2) {
                    bits[i][counter] = 1;
                }
                counter+=1;
            }
        }
        return bits;
    }

    public static short[] pskdemod_helper(double[][] fft_spectrum, int [] valid_subcarrier) {
        int num_valid = valid_subcarrier.length;
        short[] bit_decode = new short[num_valid];

        for(int i = 0; i< num_valid; ++i){
            int bin_index = valid_subcarrier[i];
            try {
                if (fft_spectrum[0][bin_index] > 0) {
                    bit_decode[i] = 0;
                } else {
                    bit_decode[i] = 1;
                }
            }
            catch(Exception e) {

            }
        }
        return bit_decode;
    }

    public double[][] dpskmod(short[] bits) {
        return null;
    }

    public short[] dpskdemod(double[] fft_spectrum, double f1, double f2) {
        return null;
    }
}
