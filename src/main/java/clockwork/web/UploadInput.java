package clockwork.web;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One credited 32 KiB request chunk at a time. No file/network work runs on
 * the WebSocket callback. Close, stalled send, or cancellation wakes readers. */
final class UploadInput extends InputStream {
    interface Credit { void request() throws IOException; }
    private final long length;
    private final Credit credit;
    private byte[] chunk;
    private int offset,sequence;
    private long received;
    private boolean requested,ended,closed;
    UploadInput(long length,Credit credit)throws IOException { if(length<=0||length>UploadedAudio.MUSIC_MAX_BYTES)throw new IOException("Invalid sound upload length");this.length=length;this.credit=credit; }
    synchronized void accept(int seq,byte[] bytes)throws IOException {
        if(closed)return;
        if(ended||!requested||chunk!=null||seq!=sequence++||bytes.length==0||bytes.length>32768||received+bytes.length>length)throw new IOException("Invalid upload chunk/credit");
        received+=bytes.length;requested=false;chunk=bytes;offset=0;notifyAll();
    }
    synchronized void end()throws IOException { if(closed)return;if(ended||received!=length)throw new IOException("Incomplete sound upload");ended=true;notifyAll(); }
    @Override public int read()throws IOException {byte[] single=new byte[1];return read(single,0,1)<0?-1:single[0]&255;}
    @Override public int read(byte[] bytes,int start,int count)throws IOException {
        Objects.checkFromIndexSize(start,count,bytes.length);if(count==0)return 0;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(true){
            boolean send=false;
            synchronized(this){
                if(closed)throw new InterruptedIOException("Sound upload canceled");
                if(chunk!=null){int size=Math.min(count,chunk.length-offset);System.arraycopy(chunk,offset,bytes,start,size);offset+=size;if(offset==chunk.length){chunk=null;offset=0;}return size;}
                if(ended)return -1;
                if(!requested&&received<length){requested=true;send=true;}
                if(!send){long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IOException("Sound upload stalled; no sound was saved");try{TimeUnit.NANOSECONDS.timedWait(this,remaining);}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new InterruptedIOException("Sound upload canceled");}}
            }
            if(send)credit.request();
        }
    }
    @Override public synchronized void close(){closed=true;chunk=null;notifyAll();}
}
