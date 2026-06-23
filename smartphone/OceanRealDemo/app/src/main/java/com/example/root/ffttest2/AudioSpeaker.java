package com.example.root.ffttest2;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

public class AudioSpeaker extends Thread {

    AudioTrack track1;
    Context mycontext;
    short[] samples;
    int speakerType;
    AudioManager man;
    int loops;
    int samplingFreq;

    int[] streams;

    int preamble_length;
    public AudioSpeaker(Context mycontext, short[] samples, int samplingFreq, int loops, int preamble_length, boolean top) {
        this.loops = loops;
        this.preamble_length=preamble_length;
        this.samplingFreq= samplingFreq;

        this.speakerType = AudioManager.STREAM_SYSTEM; // streamType – the type of the audio stream

        this.mycontext = mycontext;
        man = (AudioManager)mycontext.getSystemService(Context.AUDIO_SERVICE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            streams = new int[]{
                    AudioManager.STREAM_MUSIC,
                    AudioManager.STREAM_ACCESSIBILITY,
                    AudioManager.STREAM_ALARM,
                    AudioManager.STREAM_DTMF,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.STREAM_RING,
                    AudioManager.STREAM_SYSTEM,
                    AudioManager.STREAM_VOICE_CALL
            };
        } else {
            streams = new int[]{
                    AudioManager.STREAM_MUSIC,
                    AudioManager.STREAM_ALARM,
                    AudioManager.STREAM_DTMF,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.STREAM_RING,
                    AudioManager.STREAM_SYSTEM,
                    AudioManager.STREAM_VOICE_CALL
            };
        }

        for (int i : streams) {
            try {
                man.adjustStreamVolume(i, AudioManager.ADJUST_MUTE, 0);
            } catch (Exception e) {
                Log.e("AudioSpeaker", "Muting stream " + i + " failed: " + e.getMessage());
            }
        }

        try {
            man.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception e) {
            Log.e("AudioSpeaker", "Unmuting music stream failed: " + e.getMessage());
        }

        try {
            man.setStreamVolume(AudioManager.STREAM_MUSIC, (int)(man.getStreamMaxVolume(speakerType)), 0);
        } catch (Exception e) {
            Log.e("AudioSpeaker", "Setting music volume failed: " + e.getMessage());
        }

        try {
            man.adjustStreamVolume(speakerType, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception e) {
            Log.e("AudioSpeaker", "Unmuting speaker stream failed: " + e.getMessage());
        }

        try {
            man.setStreamVolume(speakerType, (int)(man.getStreamMaxVolume(speakerType)), 0);
        } catch (Exception e) {
            Log.e("AudioSpeaker", "Setting speaker volume failed: " + e.getMessage());
        }

        write(samples);
        try {
            man.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        } catch (Exception e) {
            Log.e("AudioSpeaker", "Setting ringer mode failed: " + e.getMessage());
        }
    }

    public void write(short[] samples) {
        this.samples = samples;
        track1 = new AudioTrack(speakerType,
                samplingFreq,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, // PCM 16 bit per sample. Guaranteed to be supported by devices.
                samples.length * 2,  // Each sample is 2 8-bit bytes, so the buffer length is samples * 2.
                AudioTrack.MODE_STATIC // mode – streaming or static buffer.
        );
        track1.write(samples,0,samples.length);
    }

    public void play(double vol) {
        try {
            track1.setLoopPoints(preamble_length, samples.length, loops);
            track1.setVolume((float)vol);
            track1.play();
        }catch(Exception e) {
            Log.e("asdf",e.toString());
        }
    }

    public void reset() {
        track1.stop();
        track1.reloadStaticData();
    }

    public void run() {
        Log.e("asdf","set loop points ");
    }

    public void pause() {
        track1.pause();
    }

    public void release() {
        if (track1 != null) {
            try {
                track1.stop();
                track1.release();
                track1 = null;
            } catch (Exception e) {
                Log.e("AudioSpeaker", "Release failed: " + e.getMessage());
            }
        }
    }
}
