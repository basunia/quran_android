package com.quran.labs.androidquran.ui;

import android.content.*;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.quran.labs.androidquran.R;
import com.quran.labs.androidquran.common.QuranAyah;
import com.quran.labs.androidquran.data.QuranInfo;
import com.quran.labs.androidquran.service.AudioService;
import com.quran.labs.androidquran.service.QuranAudioService;
import com.quran.labs.androidquran.service.QuranDownloadService;
import com.quran.labs.androidquran.service.util.AudioRequest;
import com.quran.labs.androidquran.service.util.DownloadAudioRequest;
import com.quran.labs.androidquran.service.util.ServiceIntentHelper;
import com.quran.labs.androidquran.ui.fragment.ImageCacheFragment;
import com.quran.labs.androidquran.ui.fragment.QuranPageFragment;
import com.quran.labs.androidquran.ui.helpers.QuranDisplayHelper;
import com.quran.labs.androidquran.ui.helpers.QuranPageAdapter;
import com.quran.labs.androidquran.ui.helpers.QuranPageWorker;
import com.quran.labs.androidquran.util.AudioUtils;
import com.quran.labs.androidquran.util.QuranFileUtils;
import com.quran.labs.androidquran.util.QuranScreenInfo;
import com.quran.labs.androidquran.util.QuranSettings;
import com.quran.labs.androidquran.widgets.AudioStatusBar;

import java.io.File;
import java.io.Serializable;

public class PagerActivity extends SherlockFragmentActivity implements
        AudioStatusBar.AudioBarListener {
   private static final String TAG = "PagerActivity";
   private static final String AUDIO_DOWNLOAD_KEY = "AUDIO_DOWNLOAD_KEY";
   private static final String LAST_AUDIO_DL_REQUEST = "LAST_AUDIO_DL_REQUEST";
   private static final String LAST_READ_PAGE = "LAST_READ_PAGE";

   private QuranPageWorker mWorker = null;
   private SharedPreferences mPrefs = null;
   private long mLastPopupTime = 0;
   private boolean mIsActionBarHidden = true;
   private AudioStatusBar mAudioStatusBar = null;
   private ViewPager mViewPager = null;
   private QuranPageAdapter mPagerAdapter = null;
   private boolean mShouldReconnect = false;
   private DownloadAudioRequest mLastAudioDownloadRequest = null;

   @Override
   public void onCreate(Bundle savedInstanceState){
      setTheme(R.style.Theme_Sherlock);
      getSherlock().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);

      super.onCreate(savedInstanceState);

      // make sure to remake QuranScreenInfo if it doesn't exist, as it
      // is needed to get images, to get the highlighting db, etc.
      QuranScreenInfo.getOrMakeInstance(this);

      int page = -1;

      if (savedInstanceState != null){
         android.util.Log.d(TAG, "non-null saved instance state!");
         Serializable lastAudioRequest =
                 savedInstanceState.getSerializable(LAST_AUDIO_DL_REQUEST);
         if (lastAudioRequest != null &&
                 lastAudioRequest instanceof DownloadAudioRequest){
            android.util.Log.d(TAG, "restoring request from saved instance!");
            mLastAudioDownloadRequest = (DownloadAudioRequest)lastAudioRequest;
         }
         page = savedInstanceState.getInt(LAST_READ_PAGE, -1);
         if (page != -1){ page = 604 - page; }
      }
      
      getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
      mPrefs = PreferenceManager.getDefaultSharedPreferences(
            getApplicationContext());

      getSupportActionBar().hide();
      mIsActionBarHidden = true;

      int background = getResources().getColor(
              R.color.transparent_actionbar_color);
      setContentView(R.layout.quran_page_activity);
      getSupportActionBar().setBackgroundDrawable(
              new ColorDrawable(background));
      mAudioStatusBar = (AudioStatusBar)findViewById(R.id.audio_area);
      mAudioStatusBar.setAudioBarListener(this);

      Intent intent = getIntent();
      Bundle extras = intent.getExtras();
      if (extras != null && page == -1){ page = 604 - extras.getInt("page"); }
      updateActionBarTitle(604 - page);

      mWorker = new QuranPageWorker(this);
      mLastPopupTime = System.currentTimeMillis();
      mPagerAdapter = new QuranPageAdapter(
              getSupportFragmentManager());
      mViewPager = (ViewPager)findViewById(R.id.quran_pager);
      mViewPager.setAdapter(mPagerAdapter);
      mViewPager.setOnPageChangeListener(new OnPageChangeListener(){

         @Override
         public void onPageScrollStateChanged(int state) {}

         @Override
         public void onPageScrolled(int position, float positionOffset,
               int positionOffsetPixels) {
         }

         @Override
         public void onPageSelected(int position) {
            Log.d(TAG, "onPageSelected(): " + position);
            int page = 604 - position;
            QuranSettings.getInstance().setLastPage(page);
            QuranSettings.save(mPrefs);
            if (QuranSettings.getInstance().isDisplayMarkerPopup()){
               mLastPopupTime = QuranDisplayHelper.displayMarkerPopup(
                       PagerActivity.this, page, mLastPopupTime);
            }
            updateActionBarTitle(page);
         }
      });

      mViewPager.setCurrentItem(page);

      // just got created, need to reconnect to service
      mShouldReconnect = true;
   }

   @Override
   public void onResume(){
      LocalBroadcastManager.getInstance(this).registerReceiver(
              mAudioReceiver,
              new IntentFilter(AudioService.AudioUpdateIntent.INTENT_NAME));
      LocalBroadcastManager.getInstance(this).registerReceiver(
              mDownloadReceiver, new IntentFilter(
              QuranDownloadService.ProgressIntent.INTENT_NAME));

      super.onResume();
      if (mShouldReconnect){
         startService(new Intent(AudioService.ACTION_CONNECT));
         mShouldReconnect = false;
      }
   }

   @Override
   public void onPause(){
      LocalBroadcastManager.getInstance(this)
              .unregisterReceiver(mAudioReceiver);
      LocalBroadcastManager.getInstance(this)
              .unregisterReceiver(mDownloadReceiver);
      super.onPause();
   }

   private void updateActionBarTitle(int page){
      String sura = QuranInfo.getSuraNameFromPage(this, page, true);
      ActionBar actionBar = getSupportActionBar();
      actionBar.setTitle(sura);
      String desc = QuranInfo.getPageSubtitle(this, page);
      actionBar.setSubtitle(desc);
   }

   BroadcastReceiver mAudioReceiver = new BroadcastReceiver(){
      @Override
      public void onReceive(Context context, Intent intent){
         if (intent != null){
            int state = intent.getIntExtra(
                    AudioService.AudioUpdateIntent.STATUS, -1);
            int sura = intent.getIntExtra(
                    AudioService.AudioUpdateIntent.SURA, -1);
            int ayah = intent.getIntExtra(
                    AudioService.AudioUpdateIntent.AYAH, -1);
            if (state == AudioService.AudioUpdateIntent.PLAYING){
               mAudioStatusBar.switchMode(AudioStatusBar.PLAYING_MODE);
               highlightAyah(sura, ayah);
            }
            else if (state == AudioService.AudioUpdateIntent.PAUSED){
               mAudioStatusBar.switchMode(AudioStatusBar.PAUSED_MODE);
               highlightAyah(sura, ayah);
            }
            else if (state == AudioService.AudioUpdateIntent.STOPPED){
               mAudioStatusBar.switchMode(AudioStatusBar.STOPPED_MODE);
               unhighlightAyah();

               Serializable qi = intent.getSerializableExtra(
                       AudioService.EXTRA_PLAY_INFO);
               if (qi != null){
                  // this means we stopped due to missing audio
               }
            }
         }
      }
   };

   BroadcastReceiver mDownloadReceiver = new BroadcastReceiver(){
      @Override
      public void onReceive(Context context, Intent intent){
         if (intent != null){
            int type = intent.getIntExtra(
                    QuranDownloadService.ProgressIntent.DOWNLOAD_TYPE,
                    QuranDownloadService.DOWNLOAD_TYPE_UNDEF);
            if (QuranDownloadService.DOWNLOAD_TYPE_AUDIO == type){
               String state = intent.getStringExtra(
                       QuranDownloadService.ProgressIntent.STATE);
               if (state != null){
                  if (QuranDownloadService.STATE_DOWNLOADING.equals(state)){
                     int progress = intent.getIntExtra(
                             QuranDownloadService.ProgressIntent.PROGRESS, -1);
                     mAudioStatusBar.switchMode(
                             AudioStatusBar.DOWNLOADING_MODE);
                     mAudioStatusBar.setProgress(progress);
                  }
                  else if (QuranDownloadService
                          .STATE_PROCESSING.equals(state)){
                     mAudioStatusBar.setProgressText(
                             getString(R.string.extracting_title), false);
                     mAudioStatusBar.setProgress(-1);
                  }
                  else if (QuranDownloadService.STATE_SUCCESS.equals(state)){
                     playAudioRequest(mLastAudioDownloadRequest);
                  }
                  else if (QuranDownloadService.STATE_ERROR.equals(state)){
                     String s = getString(ServiceIntentHelper
                                     .getErrorResourceFromDownloadIntent(intent));
                     mAudioStatusBar.setProgressText(s, true);
                  }
                  else if (QuranDownloadService
                          .STATE_ERROR_WILL_RETRY.equals(state)){
                     int errorId = ServiceIntentHelper
                             .getErrorResourceFromDownloadIntent(intent);
                     mAudioStatusBar.setProgressText(getString(errorId), false);
                  }
               }
            }
         }
      }
   };

   public void toggleActionBar(){
      if (mIsActionBarHidden){
         getWindow().addFlags(
                 WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
         getWindow().clearFlags(
                 WindowManager.LayoutParams.FLAG_FULLSCREEN);
         getSupportActionBar().show();
         mAudioStatusBar.updateSelectedItem();
         mAudioStatusBar.setVisibility(View.VISIBLE);
         mIsActionBarHidden = false;
      }
      else {
         getWindow().addFlags(
               WindowManager.LayoutParams.FLAG_FULLSCREEN);
         getWindow().clearFlags(
               WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
         getSupportActionBar().hide();
         mAudioStatusBar.setVisibility(View.GONE);
         mIsActionBarHidden = true;
      }
   }
   
   public QuranPageWorker getQuranPageWorker(){
      return mWorker;
   }
   
   @Override
   protected void onDestroy() {
      android.util.Log.d(TAG, "onDestroy()");
      super.onDestroy();
   }

   @Override
   public void onSaveInstanceState(Bundle state){
      if (mLastAudioDownloadRequest != null){
         state.putSerializable(LAST_AUDIO_DL_REQUEST,
                 mLastAudioDownloadRequest);
      }
      state.putSerializable(LAST_READ_PAGE,
              604 - mViewPager.getCurrentItem());
      super.onSaveInstanceState(state);
   }

   public void highlightAyah(int sura, int ayah){
      int page = QuranInfo.getPageFromSuraAyah(sura, ayah);
      int position = 604 - page;
      if (position != mViewPager.getCurrentItem()){
         unhighlightAyah();
         mViewPager.setCurrentItem(position);
      }

      Fragment f = mPagerAdapter.getFragmentIfExists(position);
      if (f != null && f instanceof QuranPageFragment){
         QuranPageFragment qpf = (QuranPageFragment)f;
         qpf.highlightAyah(sura, ayah);
      }
   }

   public void unhighlightAyah(){
      int position = mViewPager.getCurrentItem();
      Fragment f = mPagerAdapter.getFragmentIfExists(position);
      if (f!= null && f instanceof QuranPageFragment){
         QuranPageFragment qpf = (QuranPageFragment)f;
         qpf.unhighlightAyah();
      }
   }

   @Override
   public void onPlayPressed() {
      int position = mViewPager.getCurrentItem();
      int page = 604 - position;

      int startSura = QuranInfo.PAGE_SURA_START[page - 1];
      int startAyah = QuranInfo.PAGE_AYAH_START[page - 1];
      int currentQari = mAudioStatusBar.getCurrentQari();

      QuranAyah ayah = new QuranAyah(startSura, startAyah);
      boolean streaming = false;
      if (streaming){
         playStreaming(ayah, page, currentQari);
      }
      else { downloadAndPlayAudio(ayah, page, currentQari); }
   }

   private void playStreaming(QuranAyah ayah, int page, int qari){
      String qariUrl = AudioUtils.getQariUrl(this, qari);
      String s = qariUrl + "%03d%03d" + AudioUtils.AUDIO_EXTENSION;
      AudioRequest request = new AudioRequest(s, ayah);
      play(request);

      mAudioStatusBar.switchMode(AudioStatusBar.PLAYING_MODE);
   }

   private void downloadAndPlayAudio(QuranAyah ayah, int page, int qari){
      QuranAyah endAyah = AudioUtils.getLastAyahToPlay(ayah, page,
              AudioUtils.LookAheadAmount.PAGE);
      String baseUri = AudioUtils.getLocalQariUrl(this, qari);
      if (endAyah == null || baseUri == null){ return; }

      String fileUrl = baseUri + File.separator + "%d" + File.separator +
              "%d" + AudioUtils.AUDIO_EXTENSION;
      DownloadAudioRequest request =
              new DownloadAudioRequest(fileUrl, ayah, qari, baseUri);
      request.setPlayBounds(ayah, endAyah);
      mLastAudioDownloadRequest = request;
      playAudioRequest(request);
   }

   private void playAudioRequest(DownloadAudioRequest request){
      if (request == null){
         mAudioStatusBar.switchMode(AudioStatusBar.STOPPED_MODE);
         return;
      }

      android.util.Log.d(TAG, "seeing if we can play audio request...");
      if (!QuranFileUtils.haveAyaPositionFile()){
         mAudioStatusBar.switchMode(AudioStatusBar.DOWNLOADING_MODE);

         String url = QuranFileUtils.getAyaPositionFileUrl();
         String destination = QuranFileUtils.getQuranDatabaseDirectory();
         // start the download
         String notificationTitle = getString(R.string.highlighting_database);
         Intent intent = ServiceIntentHelper.getDownloadIntent(this, url,
                 destination, notificationTitle, AUDIO_DOWNLOAD_KEY,
                 QuranDownloadService.DOWNLOAD_TYPE_AUDIO);
         startService(intent);
      }
      else if (AudioUtils.haveAllFiles(request)){
         if (!AudioUtils.shouldDownloadBasmallah(this, request)){
            android.util.Log.d(TAG, "have all files, playing!");
            request.removePlayBounds();
            play(request);
            mLastAudioDownloadRequest = null;
         }
         else {
            android.util.Log.d(TAG, "should download basmalla...");
            QuranAyah firstAyah = new QuranAyah(1, 1);
            String qariUrl = AudioUtils.getQariUrl(this, request.getQariId());
            qariUrl += "%03d%03d" + AudioUtils.AUDIO_EXTENSION;

            String notificationTitle =
                    QuranInfo.getNotificationTitle(this, firstAyah, firstAyah);
            Intent intent = ServiceIntentHelper.getDownloadIntent(this, qariUrl,
                    request.getLocalPath(), notificationTitle,
                    AUDIO_DOWNLOAD_KEY,
                    QuranDownloadService.DOWNLOAD_TYPE_AUDIO);
            intent.putExtra(QuranDownloadService.EXTRA_START_VERSE, firstAyah);
            intent.putExtra(QuranDownloadService.EXTRA_END_VERSE, firstAyah);
            startService(intent);
         }
      }
      else {
         mAudioStatusBar.switchMode(AudioStatusBar.DOWNLOADING_MODE);

         String notificationTitle = QuranInfo.getNotificationTitle(this,
                 request.getMinAyah(),request.getMaxAyah());
         String qariUrl = AudioUtils.getQariUrl(this, request.getQariId());
         qariUrl += "%03d%03d" + AudioUtils.AUDIO_EXTENSION;
         android.util.Log.d(TAG, "need to start download: " + qariUrl);

         // start service
         Intent intent = ServiceIntentHelper.getDownloadIntent(this, qariUrl,
                 request.getLocalPath(), notificationTitle, AUDIO_DOWNLOAD_KEY,
                 QuranDownloadService.DOWNLOAD_TYPE_AUDIO);
         intent.putExtra(QuranDownloadService.EXTRA_START_VERSE,
                 request.getMinAyah());
         intent.putExtra(QuranDownloadService.EXTRA_END_VERSE,
                 request.getMaxAyah());
         startService(intent);
      }
   }

   private void play(AudioRequest request){
      Intent i = new Intent(AudioService.ACTION_PLAYBACK);
      i.putExtra(AudioService.EXTRA_PLAY_INFO, request);
      i.putExtra(AudioService.EXTRA_IGNORE_IF_PLAYING, true);
      startService(i);
   }

   @Override
   public void onPausePressed() {
      startService(new Intent(AudioService.ACTION_PAUSE));
      mAudioStatusBar.switchMode(AudioStatusBar.PAUSED_MODE);
   }

   @Override
   public void onNextPressed() {
      startService(new Intent(AudioService.ACTION_SKIP));
   }

   @Override
   public void onPreviousPressed() {
      startService(new Intent(AudioService.ACTION_REWIND));
   }

   @Override
   public void onStopPressed() {
      startService(new Intent(AudioService.ACTION_STOP));
      mAudioStatusBar.switchMode(AudioStatusBar.STOPPED_MODE);
      unhighlightAyah();
   }

   @Override
   public void onCancelPressed(){
      int resId = R.string.canceling;
      mAudioStatusBar.setProgressText(getString(resId), true);
      Intent i = new Intent(this, QuranDownloadService.class);
      i.setAction(QuranDownloadService.ACTION_CANCEL_DOWNLOADS);
      startService(i);
   }
}