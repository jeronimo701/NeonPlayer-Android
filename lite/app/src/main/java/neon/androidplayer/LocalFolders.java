package neon.androidplayer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.IOException;
import java.util.*;

final class LocalFolders {
    static String title(ContentResolver resolver,Uri file,String fallback) {
        try {
            Uri query=DocumentsContract.isTreeUri(file) ?
                DocumentsContract.buildDocumentUriUsingTree(file,DocumentsContract.getTreeDocumentId(file)):file;
            try (Cursor c=resolver.query(query,new String[]{Document.COLUMN_DISPLAY_NAME},null,null,null)) {
                if (c!=null && c.moveToFirst() && c.getString(0)!=null) return c.getString(0);
            }
        } catch (RuntimeException ignored) {}
        return fallback;
    }
    static List<Library.Item> items(ContentResolver resolver,Library.Ref folder) throws IOException {
        Uri tree=Uri.parse(folder.address());
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        String[] cols={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE};
        List<Library.Item> list=new ArrayList<>();
        try (Cursor c=resolver.query(children,cols,null,null,null)) {
            if (c==null) throw new IOException("Directory is no longer available");
            while (c.moveToNext()) {
                String name=c.getString(1),mime=c.getString(2);
                if (name==null || Document.MIME_TYPE_DIR.equals(mime) ||
                    !(Sources.media(name) || mime!=null && (mime.startsWith("video/") || mime.startsWith("audio/")))) continue;
                Uri direct=DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));
                list.add(new Library.Item(new Library.Ref("local",name,direct.toString(),0,"","",false),null));
            }
        }
        list.sort(Comparator.comparing(i->i.ref().title(),LocalFolders::naturalCompare));
        return list;
    }
    static int naturalCompare(String a,String b) {
        int i=0,j=0;
        while (i<a.length() && j<b.length()) {
            char x=a.charAt(i),y=b.charAt(j);
            if (digit(x) && digit(y)) {
                int e=i,f=j;
                while (e<a.length() && digit(a.charAt(e))) e++;
                while (f<b.length() && digit(b.charAt(f))) f++;
                int m=i,n=j;
                while (m+1<e && a.charAt(m)=='0') m++;
                while (n+1<f && b.charAt(n)=='0') n++;
                int cmp=Integer.compare(e-m,f-n);
                if (cmp!=0) return cmp;
                while (m<e) {
                    cmp=Character.compare(a.charAt(m++),b.charAt(n++));
                    if (cmp!=0) return cmp;
                }
                i=e;j=f;continue;
            }
            int cmp=Character.compare(Character.toLowerCase(x),Character.toLowerCase(y));
            if (cmp!=0) return cmp;
            i++;j++;
        }
        int tail=Integer.compare(a.length()-i,b.length()-j);
        return tail!=0?tail:String.CASE_INSENSITIVE_ORDER.compare(a,b);
    }
    private static boolean digit(char c) {return c>='0' && c<='9';}
}
