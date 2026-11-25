package neon.androidplayer;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only MS-SRVS NetShareEnum over the authenticated SMB IPC$ pipe. */
final class SmbShares {
    private static final int MAX=1024*1024;
    static final class Status extends IOException {
        final int code;
        Status(int code) {super("NetrShareEnum: "+code);this.code=code;}
    }
    static List<String> list(Session session) throws IOException {
        try (PipeShare ipc=(PipeShare)session.connectShare("IPC$");
             NamedPipe pipe=ipc.open("srvsvc",SMB2ImpersonationLevel.Impersonation,
                 EnumSet.of(AccessMask.GENERIC_READ,AccessMask.GENERIC_WRITE),null,SMB2ShareAccess.ALL,
                 SMB2CreateDisposition.FILE_OPEN,null)) {
            ByteBuffer bind=bytes(56);
            bind.putShort((short)4280).putShort((short)4280).putInt(0).putInt(1);
            bind.putShort((short)0).put((byte)1).put((byte)0);
            bind.putInt(0x4b324fc8).putShort((short)0x1670).putShort((short)0x01d3)
                .put(new byte[]{0x12,0x78,0x5a,0x47,(byte)0xbf,0x6e,(byte)0xe1,(byte)0x88}).putInt(3);
            bind.putInt(0x8a885d04).putShort((short)0x1ceb).putShort((short)0x11c9)
                .put(new byte[]{(byte)0x9f,(byte)0xe8,8,0,0x2b,0x10,0x48,0x60}).putInt(2);
            byte[] ack=pipe.transact(packet(11,1,bind.array()));
            ByteBuffer a=header(ack,12,1);
            if (a.remaining()<10) throw bad();
            a.position(24); int address=a.getShort()&0xffff;
            a.position((26+address+3)&~3);
            if (a.remaining()<8 || a.getInt()!=1 || a.getShort()!=0) throw bad();
            TreeSet<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            int resume=0;
            for (int id=2;id<34;id++) {
                ByteBuffer request=bytes(40);
                request.putInt(32).putShort((short)0).putShort((short)15);
                request.putInt(0).putInt(1).putInt(1).putInt(0x20000).putInt(0).putInt(0)
                    .putInt(32768).putInt(0x20004);
                ByteBuffer stub=bytes(44); stub.put(request.array()).putInt(resume);
                stub.putInt(0,36);
                ByteBuffer result=receive(pipe,packet(0,id,stub.array()),id);
                int next=parse(result,names);
                if (next==-1) return new ArrayList<>(names);
                if (next==resume) throw bad();
                resume=next;
            }
            throw bad();
        } catch (IllegalArgumentException | BufferUnderflowException e) {throw bad();}
    }
    private static int parse(ByteBuffer b,Set<String> out) throws IOException {
        if (b.getInt()!=1 || b.getInt()!=1) throw bad();
        boolean container=b.getInt()!=0;
        if (container) {
            int count=b.getInt(), pointer=b.getInt();
            if (count<0 || count>4096 || (count!=0 && pointer==0)) throw bad();
            if (pointer!=0) {
                if (b.getInt()!=count || b.remaining()<count*12) throw bad();
                int[] type=new int[count],name=new int[count],remark=new int[count];
                for (int i=0;i<count;i++) { name[i]=b.getInt();type[i]=b.getInt();remark[i]=b.getInt(); }
                for (int i=0;i<count;i++) {
                    String value=name[i]==0?"":string(b);
                    if (remark[i]!=0) string(b);
                    if ((type[i]&255)==0 && !value.isEmpty() && !value.endsWith("$")
                        && value.indexOf('\\')<0 && value.indexOf('/')<0) out.add(value);
                }
            }
        }
        align(b); b.getInt();
        int resume=b.getInt()==0?-1:b.getInt();
        int status=b.getInt();
        if (status==0) return -1;
        if (status==234 && resume>=0) return resume;
        throw new Status(status);
    }
    private static String string(ByteBuffer b) throws IOException {
        align(b); int max=b.getInt(), offset=b.getInt(), count=b.getInt();
        if (offset!=0 || max<count || count<1 || count>4096 || b.remaining()<count*2) throw bad();
        byte[] data=new byte[count*2]; b.get(data);
        if (data[data.length-1]!=0 || data[data.length-2]!=0) throw bad();
        return new String(data,0,data.length-2,StandardCharsets.UTF_16LE);
    }
    private static void align(ByteBuffer b) {b.position((b.position()+3)&~3);}
    private static ByteBuffer receive(NamedPipe pipe,byte[] request,int id) throws IOException {
        byte[] available=pipe.transact(request);
        ByteArrayOutputStream stub=new ByteArrayOutputStream();
        for (int fragment=0;fragment<256;fragment++) {
            while (available.length<16) available=append(pipe,available);
            int size=(available[8]&255)|((available[9]&255)<<8);
            if (size<24 || stub.size()+size>MAX) throw bad();
            while (available.length<size) available=append(pipe,available);
            ByteBuffer response=header(Arrays.copyOf(available,size),2,id);
            if (response.getShort(20)!=0) throw bad();
            stub.write(available,24,size-24);
            if ((available[3]&2)!=0) return ByteBuffer.wrap(stub.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            available=Arrays.copyOfRange(available,size,available.length);
        }
        throw bad();
    }
    private static byte[] append(NamedPipe pipe,byte[] prefix) throws IOException {
        byte[] next=new byte[65536]; int n=pipe.read(next);
        if (n<=0 || prefix.length+n>MAX) throw bad();
        byte[] data=Arrays.copyOf(prefix,prefix.length+n);
        System.arraycopy(next,0,data,prefix.length,n);return data;
    }
    private static ByteBuffer header(byte[] data,int type,int id) throws IOException {
        if (data.length<16) throw bad();
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int length=b.getShort(8)&65535;
        if (b.get(0)!=5 || b.get(2)!=type || b.getInt(4)!=16 || b.getShort(10)!=0 ||
            b.getInt(12)!=id || length<16 || length>data.length) throw bad();
        b.limit(length); b.position(16); return b;
    }
    private static byte[] packet(int type,int id,byte[] payload) {
        return bytes(16+payload.length).put((byte)5).put((byte)0).put((byte)type).put((byte)3)
            .putInt(16).putShort((short)(16+payload.length)).putShort((short)0).putInt(id).put(payload).array();
    }
    private static ByteBuffer bytes(int size) {return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);}
    private static IOException bad() {return new IOException("Сервер вернул неподдерживаемый список SMB");}
}
