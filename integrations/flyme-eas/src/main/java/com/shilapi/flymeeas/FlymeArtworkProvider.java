package com.shilapi.flymeeas;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Read-only, bounded image cache for the OEM media process. No arbitrary paths or app files. */
public final class FlymeArtworkProvider extends ContentProvider {
 static final int MAX_BYTES=4*1024*1024;
 private static final int MAX_FILES=8;
 private static File directory(Context c){return new File(c.getCacheDir(),"flyme-media-artwork");}
 static Uri icon(Context c){int icon=c.getApplicationInfo().icon;return icon==0?null:Uri.parse("android.resource://"+c.getPackageName()+"/"+icon);}
 static synchronized Uri publish(Context c,byte[] bytes)throws IOException {
  if(bytes==null||bytes.length==0)return null;
  if(bytes.length>MAX_BYTES)throw new IOException("artwork exceeds 4 MiB");
  StringBuilder hash=new StringBuilder();
  try{for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b));}
  catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
  File dir=directory(c);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("artwork cache unavailable");
  File output=new File(dir,"cover-"+hash+".png");
  if(!output.isFile()){
   BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
   BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
   if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>8192||bounds.outHeight>8192)throw new IOException("invalid artwork dimensions");
   BitmapFactory.Options options=new BitmapFactory.Options();
   options.inSampleSize=1;
   while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;
   Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
   if(bitmap==null)throw new IOException("artwork decode failed");
   File temp=File.createTempFile("cover-",".tmp",dir);
   try{
    try(FileOutputStream stream=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("artwork encode failed");}
    if(!temp.renameTo(output))throw new IOException("artwork publish failed");
   }finally{bitmap.recycle();if(temp.exists())temp.delete();}
  }
  output.setLastModified(System.currentTimeMillis());
  File[] files=dir.listFiles((d,name)->name.matches("cover-[a-f0-9]{64}\\.png"));
  if(files!=null&&files.length>MAX_FILES){Arrays.sort(files,Comparator.comparingLong(File::lastModified));int remove=files.length-MAX_FILES;for(File f:files){if(remove<=0)break;if(!f.equals(output)&&f.delete())remove--;}}
  return new Uri.Builder().scheme("content").authority(c.getPackageName()+".flyme-artwork").appendPath(output.getName()).build();
 }
 @Override public boolean onCreate(){return true;}
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
  Context c=getContext();
  if(c==null||!"r".equals(mode)||!"content".equals(uri.getScheme())||!(c.getPackageName()+".flyme-artwork").equals(uri.getAuthority())||uri.getPathSegments().size()!=1||!uri.getLastPathSegment().matches("cover-[a-f0-9]{64}\\.png"))throw new FileNotFoundException("unsupported artwork request");
  return ParcelFileDescriptor.open(new File(directory(c),uri.getLastPathSegment()),ParcelFileDescriptor.MODE_READ_ONLY);
 }
 @Override public String getType(Uri uri){return "image/png";}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){return null;}
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("read only");}
 @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("read only");}
 @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("read only");}
}
