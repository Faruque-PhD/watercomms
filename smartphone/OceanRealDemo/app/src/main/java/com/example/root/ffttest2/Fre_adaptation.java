package com.example.root.ffttest2;

import android.util.Log;

import java.util.Arrays;
import java.util.LinkedList;
import java.lang.Math;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Fre_adaptation {
    public static int[] select_fre_bins(double[] SNR, double threshold) {
        int total_bins = SNR.length;
        int[] select_idx = new int[]{-1, -1};
        
        int MIN_CARRIERS = 20;
        if (total_bins < MIN_CARRIERS) MIN_CARRIERS = total_bins;

        // Phase 1: Try to find a contiguous block of at least MIN_CARRIERS that meets the threshold
        for(int L = total_bins; L >= MIN_CARRIERS ; L--){
            double incre = Utils.mag2db((double)(total_bins)/(double)(L))/2*Constants.FreAdaptScaleFactor;
            int best_idx = -1;
            double best_valley = -1000;
            for(int i = 0; i < total_bins - L + 1 ; ++i){
                double valley = Utils.min(SNR, i, i+L) + incre;
                if(valley >= threshold && valley > best_valley){
                    best_idx = i;
                    best_valley = valley;
                }
            }
            if(best_idx >= 0){
                select_idx[0] = best_idx;
                select_idx[1] = best_idx + L - 1;
                return select_idx;
            }
        }

        // Phase 2: Fallback - if no block of MIN_CARRIERS meets threshold, 
        // find the "best" block of MIN_CARRIERS (highest minimum SNR)
        int best_idx = -1;
        double best_min_snr = -1000;
        for(int i = 0; i < total_bins - MIN_CARRIERS + 1 ; ++i){
            double min_snr = Utils.min(SNR, i, i + MIN_CARRIERS);
            if(min_snr > best_min_snr) {
                best_idx = i;
                best_min_snr = min_snr;
            }
        }
        
        if (best_idx >= 0) {
            select_idx[0] = best_idx;
            select_idx[1] = best_idx + MIN_CARRIERS - 1;
            return select_idx;
        }

        // Phase 3: Absolute fallback for extremely small bandwidths
        for(int L = MIN_CARRIERS - 1; L > 0 ; L--){
            double incre = Utils.mag2db((double)(total_bins)/(double)(L))/2*Constants.FreAdaptScaleFactor;
            best_idx = -1;
            double best_valley = -1000;
            for(int i = 0; i < total_bins - L + 1 ; ++i){
                double valley = Utils.min(SNR, i, i+L) + incre;
                if(valley >= threshold && valley > best_valley){
                    best_idx = i;
                    best_valley = valley;
                }
            }
            if(best_idx >= 0){
                select_idx[0] = best_idx;
                select_idx[1] = best_idx + L - 1;
                return select_idx;
            }
        }

        return select_idx;
    }
}