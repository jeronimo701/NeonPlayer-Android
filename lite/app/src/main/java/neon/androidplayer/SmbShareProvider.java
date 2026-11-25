package neon.androidplayer;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.MediaDataSource;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exports a single read-only file through Android's per-URI grants. No LAN listener. */
public final class SmbShareProvider extends ContentProvider {
    private static final long TOKEN_MS=4*60*60*1000L;
    private static final int MAX_TOKENS=32;
    private static final Semaphore OPEN_FILES=new Semaphore(8);
    private static final Map<String,SharedFile> files=new LinkedHashMap<>();
    private record SharedFile(Sources.Smb source,Sources.Entry file,String mime,long deadline) {}

    static Uri grantUri(Context context,Sources.Smb source,Sources.Entry entry) {
        if (entry.folder() || Build.VERSION.SDK_INT<26) throw new IllegalArgumentException("Not a shareable file");
        String token=UUID.randomUUID().toString();
        long now=SystemClock.elapsedRealtime();
        SharedFile target=new SharedFile(source,entry,mime(entry.label()),now+TOKEN_MS);
        synchronized (files) {
            files.values().removeIf(v -> v.deadline()<=now);
            if (files.size()>=MAX_TOKENS) files.remove(files.keySet().iterator().next());
            files.put(token,target);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            synchronized (files) { files.remove(token); }
        },TOKEN_MS);
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
            .authority(context.getPackageName()+".smbshare").appendPath(token).appendPath(entry.label()).build();
    }

    static Uri smbLink(Sources.Smb source,String path) {
        String host=source.host();
        if (host.contains(":") && !host.startsWith("[")) host="["+host+"]";
        String authority=source.port()==445?host:host+":"+source.port();
        Uri.Builder link=new Uri.Builder().scheme("smb").encodedAuthority(authority).appendPath(source.share());
        for (String segment:path.split("\\\\")) if (!segment.isEmpty()) link.appendPath(segment);
        return link.build();
    }

    private static String mime(String name) {
        int dot=name.lastIndexOf('.');
        String ext=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        if (ext.equals("mkv")) return "video/x-matroska";
        if (ext.equals("m4v")) return "video/mp4";
        if (ext.equals("ts") || ext.equals("m2ts")) return "video/mp2t";
        String detected=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (detected!=null && (detected.startsWith("video/") || detected.startsWith("audio/"))) return detected;
        return switch (ext) {
            case "mp3","m4a","flac","ogg","opus","wav","aac" -> "audio/*";
            default -> "video/*";
        };
    }

    private static SharedFile lookup(Uri uri) {
        List<String> path=uri.getPathSegments();
        if (path.size()!=2) return null;
        synchronized (files) {
            SharedFile entry=files.get(path.get(0));
            if (entry==null) return null;
            if (entry.deadline()<=SystemClock.elapsedRealtime()) {
                files.remove(path.get(0)); return null;
            }
            return entry.file().label().equals(path.get(1))?entry:null;
        }
    }

    @Override public boolean onCreate() {return true;}
    @Override public String getType(Uri uri) {
        SharedFile entry=lookup(uri); return entry==null?null:entry.mime();
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String order) {
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns,1);
        SharedFile entry=lookup(uri);
        if (entry!=null) {
            Object[] row=new Object[columns.length];
            for (int i=0;i<columns.length;i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]=entry.file().label();
                else if (OpenableColumns.SIZE.equals(columns[i]) && entry.file().size()>=0) row[i]=entry.file().size();
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        SharedFile entry=lookup(uri);
        if (entry==null) throw new FileNotFoundException("Reopen this video from Neon Lite");
        if (Build.VERSION.SDK_INT<26 || getContext()==null) throw new FileNotFoundException("Android 8 required");
        if (!OPEN_FILES.tryAcquire()) throw new FileNotFoundException("Too many open SMB files");
        return Api26.open(getContext(),entry);
    }
    @Override public Uri insert(Uri u,ContentValues v) {throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a) {throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a) {throw new UnsupportedOperationException();}

    private static final class Api26 {
        static ParcelFileDescriptor open(Context context,SharedFile entry) throws FileNotFoundException {
            MediaDataSource source=null;
            HandlerThread reader=null;
            FileProxy proxy=null;
            try {
                source=entry.source().file(entry.file().path());
                long length=source.getSize();
                if (length<0) throw new IllegalStateException("Unknown SMB size");
                reader=new HandlerThread("Neon SMB share");
                reader.start();
                proxy=new FileProxy(source,length,reader);
                StorageManager storage=context.getSystemService(StorageManager.class);
                if (storage==null) throw new IllegalStateException("Storage unavailable");
                return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,proxy,new Handler(reader.getLooper()));
            } catch (Exception e) {
                if (proxy!=null) proxy.onRelease();
                else dispose(source,reader);
                throw new FileNotFoundException("Could not open the SMB file. Reopen it from Neon Lite.");
            }
        }
        private static void dispose(MediaDataSource source,HandlerThread reader) {
            try { if (source!=null) source.close(); }
            catch (Exception ignored) {}
            finally {
                if (reader!=null) reader.quitSafely();
                OPEN_FILES.release();
            }
        }
        private static final class FileProxy extends ProxyFileDescriptorCallback {
            private final MediaDataSource source;
            private final long length;
            private final HandlerThread reader;
            private final AtomicBoolean closed=new AtomicBoolean();
            FileProxy(MediaDataSource source,long length,HandlerThread reader) {
                this.source=source; this.length=length; this.reader=reader;
            }
            @Override public long onGetSize() {return length;}
            @Override public int onRead(long offset,int size,byte[] data) throws ErrnoException {
                if (closed.get() || offset<0 || size<0 || size>data.length)
                    throw new ErrnoException("SMB read",OsConstants.EIO);
                if (offset>=length) return 0;
                try {
                    return Math.max(0,source.readAt(offset,data,0,(int)Math.min(size,length-offset)));
                } catch (Exception e) {
                    throw new ErrnoException("SMB read",OsConstants.EIO);
                }
            }
            @Override public void onRelease() {
                if (closed.compareAndSet(false,true)) dispose(source,reader);
            }
        }
    }
}
