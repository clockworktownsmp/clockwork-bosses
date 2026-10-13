package clockwork.web;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Complete single-stream Ogg/Vorbis validation, followed by bounded PCM decode.
 * No speakers, PCM cache or native/client library is used. One admitted upload
 * owns this state; page/header/codebook/duration/CPU budgets bound untrusted work. */
final class UploadedAudio {
    static final int MAX_SECONDS=600, MAX_RATE=96000, MAX_PACKET=256*1024;
    static final int CLIP_MAX_BYTES=1024*1024-1024, MUSIC_MAX_BYTES=8*1024*1024-1024, CLIP_MAX_SECONDS=30, CLIP_PCM_MAX_BYTES=8*1024*1024;
    private static final int[] CRC=crcTable();
    record Details(int channels,int rate,double seconds){}
    static Details validate(byte[] bytes) throws IOException {
        return validate(bytes,false);
    }
    static Details validate(byte[] bytes,boolean music) throws IOException {
        int maxSeconds=music?MAX_SECONDS:CLIP_MAX_SECONDS;
        if(bytes.length==0||bytes.length>(music?MUSIC_MAX_BYTES:CLIP_MAX_BYTES))throw new IOException(music?"Music must be below 8 MiB":"Sound effects and voice lines must be below 1 MiB; choose Music for a longer track");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        Info info=new Info();info.init();Comment comments=new Comment();comments.init();DspState dsp=new DspState();Block block=new Block(dsp);
        ByteArrayOutputStream packetBytes=new ByteArrayOutputStream();
        int offset=0,sequence=0,serial=0,packets=0,pages=0,maximumBlock=8192;long granule=-1,samples=0;boolean continued=false,eos=false;
        float[][][] pcm=new float[1][][];int[] indices=null;
        try {
            while(offset<bytes.length){
                budget(deadline);
                if(eos||++pages>4096||offset+27>bytes.length||!magic(bytes,offset,"OggS")||bytes[offset+4]!=0)throw invalid();
                int flags=bytes[offset+5]&255,segments=bytes[offset+26]&255,header=27+segments,length=0;
                if((flags&~7)!=0||((flags&1)!=0)!=continued||segments==0||offset+header>bytes.length)throw invalid();
                for(int i=0;i<segments;i++)length+=bytes[offset+27+i]&255;
                if(offset+header+length>bytes.length)throw invalid();
                var page=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                int currentSerial=page.getInt(offset+14),currentSequence=page.getInt(offset+18);
                if(pages==1){serial=currentSerial;if((flags&2)==0||currentSequence!=0)throw invalid();}
                else if((flags&2)!=0||currentSerial!=serial)throw invalid();
                if(currentSequence!=sequence++||checksum(bytes,offset,header+length)!=page.getInt(offset+22))throw invalid();
                long currentGranule=page.getLong(offset+6);
                if(currentGranule<-1||currentGranule>=0&&currentGranule<granule)throw invalid();
                if(currentGranule>=0)granule=currentGranule;
                if(packets>=3&&granule>(long)info.rate*maxSeconds)throw new IOException(music?"Music exceeds the 10-minute duration limit":"Sound effects and voice lines cannot exceed 30 seconds; choose Music for a longer track");
                int cursor=offset+header;
                for(int i=0;i<segments;i++){
                    int size=bytes[offset+27+i]&255;
                    if(packetBytes.size()+size>MAX_PACKET)throw new IOException("OGG packet exceeds the validation limit");
                    packetBytes.write(bytes,cursor,size);cursor+=size;continued=size==255;
                    if(continued)continue;
                    byte[] data=packetBytes.toByteArray();packetBytes.reset();
                    Packet packet=new Packet();packet.packet_base=data;packet.packet=0;packet.bytes=data.length;packet.packetno=packets;packet.b_o_s=packets==0?1:0;
                    packet.e_o_s=(flags&4)!=0&&i==segments-1?1:0;packet.granulepos=i==segments-1?currentGranule:-1;
                    if(packets<3){
                        if(data.length<7||data[0]!=(packets*2+1)||!magic(data,1,"vorbis"))throw new IOException("Upload OGG Vorbis audio; Opus, video and other formats are not supported by Minecraft");
                        if(packets==0){
                            if(data.length!=30)throw invalid();
                            int channels=data[11]&255,rate=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(12),sizes=data[28]&255;
                            if(channels<1||channels>2||rate<8000||rate>MAX_RATE||(sizes&15)<6||(sizes&15)>(sizes>>4)||(sizes>>4)>13||(data[29]&1)!=1)throw new IOException("Use mono/stereo Vorbis at 8–96 kHz with valid block sizes");
                            maximumBlock=1<<(sizes>>4);
                        }
                        if(packets==1&&!VorbisSetup.comment(data,data.length))throw invalid();
                        if(packets==2)VorbisSetup.check(data,info.channels,maximumBlock);
                        if(info.synthesis_headerin(comments,packet)!=0)throw invalid();
                        if(packets==2){if(dsp.synthesis_init(info)!=0)throw invalid();block.init(dsp);indices=new int[info.channels];}
                    }else{
                        if(data.length==0||(data[0]&1)!=0||block.synthesis(packet)!=0)throw invalid();
                        if(dsp.synthesis_blockin(block)!=0)throw invalid();
                        int count;
                        while((count=dsp.synthesis_pcmout(pcm,indices))>0){
                            samples+=count;if(samples>(long)info.rate*maxSeconds+8192||!music&&samples*info.channels*2>CLIP_PCM_MAX_BYTES)throw new IOException("OGG exceeds Minecraft's decoded sound duration/size limit");
                            for(int c=0;c<info.channels;c++)for(int s=0;s<count;s++)if(!Float.isFinite(pcm[0][c][indices[c]+s]))throw invalid();
                            dsp.synthesis_read(count);budget(deadline);
                        }
                    }
                    packets++;budget(deadline);
                }
                eos=(flags&4)!=0;if(eos&&continued)throw invalid();offset+=header+length;
            }
            if(!eos||continued||packets<5||samples==0||granule<=0||granule>samples+8192)throw invalid();
            return new Details(info.channels,info.rate,granule/(double)info.rate);
        }catch(RuntimeException malformed){throw new IOException("OGG contains invalid or unsupported Vorbis audio",malformed);}
        finally {block.clear();dsp.clear();info.clear();}
    }
    private static boolean magic(byte[] data,int offset,String value){byte[] wanted=value.getBytes(StandardCharsets.US_ASCII);return offset+wanted.length<=data.length&&Arrays.equals(data,offset,offset+wanted.length,wanted,0,wanted.length);}
    private static IOException invalid(){return new IOException("OGG is corrupt, truncated, or not a complete single Vorbis audio stream");}
    private static void budget(long deadline) throws IOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Sound validation canceled");if(System.nanoTime()>deadline)throw new IOException("OGG validation exceeded its processing limit");}
    private static int checksum(byte[] data,int offset,int length){int crc=0;for(int i=0;i<length;i++){int value=i>=22&&i<26?0:data[offset+i]&255;crc=(crc<<8)^CRC[((crc>>>24)^value)&255];}return crc;}
    private static int[] crcTable(){int[] table=new int[256];for(int i=0;i<256;i++){int r=i<<24;for(int j=0;j<8;j++)r=(r<<1)^((r<0)?0x04c11db7:0);table[i]=r;}return table;}
}
