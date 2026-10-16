package com.shilapi.flymeeas;
/** Real retained iPhone state. Paused remains registered; stopping the session unregisters. */
public final class PlaybackSnapshot {
 public static final PlaybackSnapshot EMPTY=new PlaybackSnapshot("","","",0,0,false);
 public final String title,artist,album;
 public final long duration,position;
 public final boolean playing;
 public PlaybackSnapshot(String title,String artist,String album,long duration,long position,boolean playing){
  this.title=title==null?"":title;this.artist=artist==null?"":artist;this.album=album==null?"":album;
  this.duration=Math.max(0,duration);this.position=Math.max(0,position);this.playing=playing;
 }
}
