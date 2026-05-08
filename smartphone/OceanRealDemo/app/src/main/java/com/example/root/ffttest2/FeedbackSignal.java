package com.example.root.ffttest2;

import static com.example.root.ffttest2.Constants.LOG;

import android.util.Log;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;

public class FeedbackSignal {
    public static int[] extractSignalHelper(double[] rec, int start_point, int m_attempt) {
        double[] preamble = PreambleGen.preamble_d();
        int end_point = start_point+preamble.length-1;
        Log.e("extract",start_point+","+end_point+","+rec.length);
        if (end_point-1 > rec.length || start_point < 0) {
            Utils.log("Error extracting preamble from feedback signal");
            FileOperations.writetofile(MainActivity.av, new int[]{-1,-1},
                    Utils.genName(Constants.SignalType.FeedbackFreqs,m_attempt)+".txt");
            return new int[]{-1,-1};
        }
        double[] preamble_rx = Utils.segment(rec, start_point, end_point);

        //////////////////////////////////////////////////////////////////////////

        int rec_start = start_point+preamble.length+Constants.ChirpGap+1;
        int rec_end = rec_start+(int)((Constants.fbackTime/1000.0)*Constants.fs)-1;
        int rec_len = (rec_end - rec_start)+1;
        Log.e(LOG, rec.length+","+rec_start+","+rec_end+","+rec_len);

        if (rec_end-1 > rec.length || rec_start < 0) {
            Utils.log("Error extracting feedback from feedback signal");
            FileOperations.writetofile(MainActivity.av, new int[]{-1,-1},
                    Utils.genName(Constants.SignalType.FeedbackFreqs,m_attempt)+".txt");
            return new int[]{-1,-1};
        }
        double[] feedback = Utils.segment(rec, rec_start-1, rec_end-1);

        int[] freqs = parse_signal(preamble_rx, feedback);
        if (freqs.length == 2 && freqs[0] != -1) {
            FileOperations.writetofile(MainActivity.av, freqs,
                    Utils.genName(Constants.SignalType.FeedbackFreqs,m_attempt)+".txt");

            freqs[0]=(int)Math.ceil(freqs[0]/(double)Constants.inc)*Constants.inc;
            freqs[1]=(int)Math.floor(freqs[1]/(double)Constants.inc)*Constants.inc;

            int[] freqs_all = expand_freqs(freqs);

            FileOperations.writetofile(MainActivity.av, freqs_all,
                    Utils.genName(Constants.SignalType.ExactFeedbackFreqs,m_attempt)+".txt");

            int[] bins_all = Utils.freqs2bins(freqs_all);

            return bins_all;
        }
        else {
            FileOperations.writetofile(MainActivity.av, new int[]{-1,-1},
                    Utils.genName(Constants.SignalType.FeedbackFreqs,m_attempt)+".txt");
            return new int[]{-1, -1};
        }
    }

    public static int[] expand_freqs(int[] freqs) {
        int freqSpacing = Constants.fs/Constants.Ns;
        int numbins = (freqs[freqs.length-1]-freqs[0])/freqSpacing;

        int[] out = new int[numbins+1];
        for (int i = 0; i <= numbins; i++) {
            out[i] = freqs[0]+(i*freqSpacing);
        }
        return out;
    }

    public static short[] encodeFeedbackSignal(int fbegin, int fend, int len_ms, boolean preamble, int m_attempt) {
        short[] preamble_sig = preamble ? PreambleGen.preamble_s() : new short[0];
        int preambleLen = preamble_sig.length;
        int feedbackLenSamples = (int)((len_ms/1000.0)*Constants.fs);
        
        int len = feedbackLenSamples;
        if (preamble) {
            len += preambleLen + Constants.ChirpGap;
        }
        short[] txsig = new short[len];

        int counter = 0;
        if (preamble) {
            for (short s : preamble_sig) {
                txsig[counter++] = s;
            }
            counter += Constants.ChirpGap;
        }

        // Modified: fbegin and fend are now absolute bin numbers
        fbegin = fbegin * Constants.inc;
        fend = fend * Constants.inc;

        fbegin=Math.round(fbegin/10)*10;
        fend=Math.round(fend/10)*10;

        // encode the feedback frequencies
        int freqs[] = new int[]{fbegin,fend};
        int fbackLen=(int)((Constants.fbackTime/1000.0)*Constants.fs);
        for (int freq = 0; freq < freqs.length; freq++) {
            int ff = freqs[freq];
            for (int i = counter; i < len; i++) {
                txsig[i] += (Math.sin(2.0 * Math.PI * ff * ((double)i / Constants.fs)))*(32767/2);
            }
        }

        short[] feedback = new short[fbackLen];
        int copyStart = counter;
        for (int i = 0; i < feedback.length; i++) {
            feedback[i] = txsig[counter++];
        }

        double[] spec_fback = Utils.fftnative_short(feedback, feedback.length);
        double[] spec_fback_db = Utils.mag2db(spec_fback);

        FileOperations.writetofile(MainActivity.av, txsig, Utils.genName(Constants.SignalType.Feedback,m_attempt)+".txt");

        // plot transmitted frequencies
        int finalFbegin = fbegin;
        int finalFend = fend;
        MainActivity.av.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Constants.gview3 != null) {
                    Display.plotSpectrum(Constants.gview3, spec_fback_db, true, MainActivity.av.getResources().getColor(R.color.purple_500),
                            "Tx feedback " + finalFbegin + "," + finalFend);
                    Display.plotVerticalLine(Constants.gview3, Constants.f_seq.get(Constants.nbin1_chanest - 2));
                    Display.plotVerticalLine(Constants.gview3, Constants.f_seq.get(Constants.nbin2_chanest + 2));
                }
            }
        });

        return txsig;
    }

    public static int[] parse_signal(double[] preamble, double[] feedback) {
        Log.e(LOG,"FeedbackSignal_parse_signal");

        double[] preamble_spec = Utils.fftnative_double(preamble, preamble.length);

        double[] feedback_spec = Utils.fftnative_double(feedback, feedback.length);

        double[] preamble_spec_db = Utils.mag2db(preamble_spec);
        double[] feedback_spec_db = Utils.mag2db(feedback_spec);

        int[] freqs= decodeFeedbackSignal(feedback_spec_db);

        if (freqs.length==2) {
            Utils.log("feedback freqs " + freqs[0] + "," + freqs[freqs.length - 1]);
        }
        else {
            Utils.log("no frequencies selected");
        }

        (MainActivity.av).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Constants.gview2 != null) {
                    Constants.gview2.removeAllSeries();
                    Constants.gview2.setTitle("");
                }
                if (Constants.gview3 != null) {
                    Constants.gview3.removeAllSeries();
                    Constants.gview3.setTitle("");
                }
                if (Constants.gview != null) {
                    Display.plotSpectrum(Constants.gview, preamble_spec_db, true, MainActivity.av.getResources().getColor(R.color.purple_500), "");

                    Display.plotVerticalLine(Constants.gview, Constants.f_seq.get(Constants.nbin1_chanest));
                    Display.plotVerticalLine(Constants.gview, Constants.f_seq.get(Constants.nbin2_chanest));
                }

                if (freqs.length==2) {
                    Display.plotSpectrum(Constants.gview2, feedback_spec_db, true, MainActivity.av.getResources().getColor(R.color.purple_500),
                            "Rx Feedback " + freqs[0] + "," + freqs[freqs.length - 1]);
                }
                else {
                    Display.plotSpectrum(Constants.gview2, feedback_spec_db, true, MainActivity.av.getResources().getColor(R.color.purple_500),
                            "Rx Feedback");
                }

                if (Constants.gview2 != null) {
                    Display.plotVerticalLine(Constants.gview2, Constants.f_seq.get(Constants.nbin1_default - 2));
                    Display.plotVerticalLine(Constants.gview2, Constants.f_seq.get(Constants.nbin2_default + 2));
                }
            }
        });

        return freqs;
    }

    public static int[] decodeFeedbackSignal(double[] smooth_sig) {
        LinkedList<Bin> allBins = new LinkedList<>();
        // Frequency resolution: fs / N_fft. Since smooth_sig.length is N_fft (7680), 
        // and it covers 0..fs, spacing is 48000/7680 = 6.25Hz.
        double spacing = (double) Constants.fs / smooth_sig.length;
        
        // 1. Find peaks ONLY in the first half of the spectrum (0..fs/2) 
        // and within a sane acoustic range (1000Hz - 8000Hz)
        int searchLimit = smooth_sig.length / 2;
        for (int i = 2; i < searchLimit; i++) {
            double freq = i * spacing;
            if (freq < 800 || freq > 8000) continue; // Ignore out-of-range noise

            if (smooth_sig[i] > smooth_sig[i-1] && smooth_sig[i] > smooth_sig[i+1] && 
                smooth_sig[i] > Constants.FEEDBACK_SNR_THRESH) {
                double prom = getProm(smooth_sig, i, i - 2, i + 2);
                allBins.add(new Bin((int)Math.round(freq), smooth_sig[i], smooth_sig[i], 0, prom));
            }
        }

        // 2. Sort by prominence + magnitude
        Collections.sort(allBins, (b1, b2) -> Double.compare(b2.prom + b2.snr, b1.prom + b1.snr));

        // Debug: Log top 5 peaks
        for (int i = 0; i < Math.min(5, allBins.size()); i++) {
            Bin b = allBins.get(i);
            Log.d("ALICE_Handshake", String.format("Peak %d: Freq=%d, Mag=%.2f, Prom=%.2f", i, b.freq, b.snr, b.prom));
        }

        if (allBins.size() >= 2) {
            // Take the two most prominent peaks
            int f1 = allBins.get(0).freq;
            int f2 = allBins.get(1).freq;
            
            // Ensure they are ordered correctly
            int[] result = (f1 < f2) ? new int[]{f1, f2} : new int[]{f2, f1};
            Log.i("ALICE", "Handshake Decoded Frequencies: " + result[0] + "Hz, " + result[1] + "Hz");
            return result;
        }
        
        if (allBins.size() == 1) return new int[]{allBins.get(0).freq, allBins.get(0).freq};
        return new int[]{-1, -1};
    }

    public static Bin search(LinkedList<Bin> bins, int freq) {
        for (Bin bin : bins) {
            if (bin.freq == freq) {
                return bin;
            }
        }
        return null;
    }

    private static class Bin {
        int freq;
        double snr;
        double signal;
        double noise;
        double prom;
        public Bin(int freq, double snr, double signal, double noise, double prom) {
            this.freq = freq;
            this.snr = snr;
            this.signal = signal;
            this.noise = noise;
            this.prom = prom;
        }
    }

    public static double getProm(double[] ar, int peakloc, int beginloc, int endloc) {
        double peakval = ar[peakloc];
        int leftmarker = 0;
        int rightmarker = endloc;
        for (int i = peakloc-1; i >= beginloc ; i--) {
            if (ar[i] >= peakval) {
                leftmarker=i;
                break;
            }
        }
        for(int i = peakloc+1; i < endloc; i++) {
            if (ar[i] >= peakval) {
                rightmarker=i;
                break;
            }
        }

        double leftmin=ar[beginloc];
        double rightmin=ar[endloc];
        for(int i = beginloc; i < leftmarker; i++) {
            if (ar[i] < leftmin) {
                leftmin=ar[i];
            }
        }

        for(int i = rightmarker; i<endloc; i++) {
            if (ar[i] < rightmin) {
                rightmin=ar[i];
            }
        }

        double ref = leftmin > rightmin ? leftmin : rightmin;

        double out = peakval - ref;

        return out;
    }
}
