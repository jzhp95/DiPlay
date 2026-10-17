package com.shilapi.flymeeas;
/** Real retained iPhone state. Paused remains registered; stopping the session unregisters. */
public final class PlaybackSnapshot {
 public static final PlaybackSnapshot EMPTY=new PlaybackSnapshot("","","",0,0,false);
 public final String title,artist,album;
 public final long duration,position;
 public final boolean playing;
 final byte[] artwork;
 public PlaybackSnapshot(String title,String artist,String album,long duration,long position,boolean playing){
  this(title,artist,album,duration,position,playing,null);
 }
 public PlaybackSnapshot(String title,String artist,String album,long duration,long position,boolean playing,byte[] artwork){
  this.artwork=artwork!=null&&artwork.length<=FlymeArtworkProvider.MAX_BYTES?artwork.clone():null;
  this.title=title==null?"":title;this.artist=artist==null?"":artist;this.album=album==null?"":album;
  this.duration=Math.max(0,duration);this.position=Math.max(0,position);this.playing=playing;
 }
 public String trackId(){return title.isEmpty()&&artist.isEmpty()&&album.isEmpty()?"":"carplay:"+title.hashCode()+":"+artist.hashCode();}
}
