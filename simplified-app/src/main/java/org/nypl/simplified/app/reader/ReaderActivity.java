package org.nypl.simplified.app.reader;

import java.io.File;
import java.net.URI;
import java.util.List;

import org.nypl.simplified.app.R;
import org.nypl.simplified.app.Simplified;
import org.nypl.simplified.app.SimplifiedReaderAppServicesType;
import org.nypl.simplified.app.reader.ReaderPaginationChangedEvent.OpenPage;
import org.nypl.simplified.app.reader.ReaderReadiumViewerSettings.ScrollMode;
import org.nypl.simplified.app.reader.ReaderReadiumViewerSettings.SyntheticSpreadMode;
import org.nypl.simplified.app.reader.ReaderTOC.TOCElement;
import org.nypl.simplified.app.utilities.ErrorDialogUtilities;
import org.nypl.simplified.app.utilities.FadeUtilities;
import org.nypl.simplified.app.utilities.LogUtilities;
import org.nypl.simplified.app.utilities.UIThread;
import org.nypl.simplified.books.core.BookID;
import org.readium.sdk.android.Container;
import org.readium.sdk.android.Package;
import org.slf4j.Logger;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.io7m.jfunctional.Option;
import com.io7m.jfunctional.OptionType;
import com.io7m.jnull.NullCheck;
import com.io7m.jnull.Nullable;

/**
 * The main reader activity for reading an EPUB.
 */

@SuppressWarnings({ "boxing", "synthetic-access" }) public final class ReaderActivity extends
  Activity implements
  ReaderHTTPServerStartListenerType,
  ReaderSimplifiedFeedbackListenerType,
  ReaderReadiumFeedbackListenerType,
  ReaderReadiumEPUBLoadListenerType,
  ReaderCurrentPageListenerType,
  ReaderTOCSelectionListenerType,
  ReaderSettingsListenerType
{
  private static final Logger LOG;
  private static final String FILE_ID;
  private static final String BOOK_ID;

  static {
    LOG = LogUtilities.getLog(ReaderActivity.class);
  }

  static {
    BOOK_ID = "org.nypl.simplified.app.ReaderActivity.book";
    FILE_ID = "org.nypl.simplified.app.ReaderActivity.file";
  }

  public static void startActivity(
    final Activity from,
    final BookID book,
    final File file)
  {
    NullCheck.notNull(file);
    final Bundle b = new Bundle();
    b.putSerializable(ReaderActivity.BOOK_ID, book);
    b.putSerializable(ReaderActivity.FILE_ID, file);
    final Intent i = new Intent(from, ReaderActivity.class);
    i.putExtras(b);
    from.startActivity(i);
  }

  private @Nullable Container                         epub_container;
  private @Nullable ReaderReadiumJavaScriptAPIType    readium_js_api;
  private @Nullable ReaderSimplifiedJavaScriptAPIType simplified_js_api;
  private @Nullable ViewGroup                         view_hud;
  private @Nullable ProgressBar                       view_loading;
  private @Nullable ProgressBar                       view_progress_bar;
  private @Nullable TextView                          view_progress_text;
  private @Nullable View                              view_root;
  private @Nullable ImageView                         view_settings;
  private @Nullable TextView                          view_title_text;
  private @Nullable ImageView                         view_toc;
  private @Nullable WebView                           view_web_view;
  private @Nullable ReaderReadiumViewerSettings       viewer_settings;
  private boolean                                     web_view_resized;

  /**
   * Apply the given color scheme to all views. Unfortunately, there does not
   * seem to be a more pleasant way, in the Android API, than manually
   * applying values to all of the views in the hierarchy.
   */

  private void applyViewerColorScheme(
    final ReaderColorScheme cs)
  {
    ReaderActivity.LOG.debug("applying color scheme");

    final View in_root = NullCheck.notNull(this.view_root);
    final TextView in_progress_text =
      NullCheck.notNull(this.view_progress_text);
    final TextView in_title_text = NullCheck.notNull(this.view_title_text);
    final ImageView in_toc = NullCheck.notNull(this.view_toc);
    final ImageView in_settings = NullCheck.notNull(this.view_settings);

    final Resources rr = NullCheck.notNull(this.getResources());
    final int main_color = rr.getColor(R.color.main_color);
    final ColorMatrixColorFilter filter =
      ReaderColorMatrix.getImageFilterMatrix(main_color);

    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        in_root.setBackgroundColor(cs.getBackgroundColor());
        in_progress_text.setTextColor(main_color);
        in_title_text.setTextColor(main_color);
        in_toc.setColorFilter(filter);
        in_settings.setColorFilter(filter);
      }
    });
  }

  private void makeInitialReadiumRequest(
    final ReaderHTTPServerType hs)
  {
    final URI reader_uri = URI.create(hs.getURIBase() + "reader.html");
    final WebView wv = NullCheck.notNull(this.view_web_view);
    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        ReaderActivity.LOG.debug(
          "making initial reader request: {}",
          reader_uri);
        wv.loadUrl(reader_uri.toString());
      }
    });
  }

  @Override protected void onActivityResult(
    final int request_code,
    final int result_code,
    final @Nullable Intent data)
  {
    super.onActivityResult(request_code, result_code, data);

    ReaderActivity.LOG.debug(
      "onActivityResult: {} {} {}",
      request_code,
      result_code,
      data);

    if (request_code == ReaderTOCActivity.TOC_SELECTION_REQUEST_CODE) {
      if (result_code == Activity.RESULT_OK) {
        final Intent nnd = NullCheck.notNull(data);
        final Bundle b = NullCheck.notNull(nnd.getExtras());
        final TOCElement e =
          NullCheck.notNull((TOCElement) b
            .getSerializable(ReaderTOCActivity.TOC_SELECTED_ID));
        this.onTOCSelectionReceived(e);
      }
    }
  }

  @Override public void onConfigurationChanged(
    final @Nullable Configuration c)
  {
    super.onConfigurationChanged(c);

    ReaderActivity.LOG.debug("configuration changed");

    final WebView in_web_view = NullCheck.notNull(this.view_web_view);
    final TextView in_progress_text =
      NullCheck.notNull(this.view_progress_text);
    final ProgressBar in_progress_bar =
      NullCheck.notNull(this.view_progress_bar);

    in_web_view.setVisibility(View.INVISIBLE);
    in_progress_text.setVisibility(View.INVISIBLE);
    in_progress_bar.setVisibility(View.INVISIBLE);

    this.web_view_resized = true;
  }

  @Override protected void onCreate(
    final @Nullable Bundle state)
  {
    super.onCreate(state);
    this.setContentView(R.layout.reader);

    final Intent i = NullCheck.notNull(this.getIntent());
    final Bundle a = NullCheck.notNull(i.getExtras());

    final File epub_file =
      NullCheck.notNull((File) a.getSerializable(ReaderActivity.FILE_ID));
    final BookID book_id =
      NullCheck.notNull((BookID) a.getSerializable(ReaderActivity.BOOK_ID));

    final SimplifiedReaderAppServicesType rs =
      Simplified.getReaderAppServices();

    final ReaderSettingsType settings = rs.getSettings();
    settings.addListener(this);

    final ReaderBookmarksType bookmarks = rs.getBookmarks();

    this.viewer_settings =
      new ReaderReadiumViewerSettings(
        SyntheticSpreadMode.AUTO,
        ScrollMode.AUTO,
        100,
        30);

    final ReaderReadiumFeedbackDispatcherType rd =
      ReaderReadiumFeedbackDispatcher.newDispatcher();
    final ReaderSimplifiedFeedbackDispatcherType sd =
      ReaderSimplifiedFeedbackDispatcher.newDispatcher();

    final TextView in_title_text =
      NullCheck.notNull((TextView) this.findViewById(R.id.reader_title_text));
    final TextView in_progress_text =
      NullCheck.notNull((TextView) this
        .findViewById(R.id.reader_position_text));
    final ProgressBar in_progress_bar =
      NullCheck.notNull((ProgressBar) this
        .findViewById(R.id.reader_position_progress));
    final ProgressBar in_loading =
      NullCheck.notNull((ProgressBar) this.findViewById(R.id.reader_loading));
    final WebView in_webview =
      NullCheck.notNull((WebView) this.findViewById(R.id.reader_webview));
    final ViewGroup in_hud =
      NullCheck.notNull((ViewGroup) this
        .findViewById(R.id.reader_hud_container));
    final ImageView in_toc =
      NullCheck.notNull((ImageView) this.findViewById(R.id.reader_toc));
    final ImageView in_settings =
      NullCheck.notNull((ImageView) this.findViewById(R.id.reader_settings));

    final View in_root = NullCheck.notNull(in_hud.getRootView());
    this.view_root = in_root;

    in_loading.setVisibility(View.VISIBLE);
    in_progress_bar.setVisibility(View.INVISIBLE);
    in_progress_text.setVisibility(View.INVISIBLE);
    in_webview.setVisibility(View.INVISIBLE);
    in_hud.setVisibility(View.INVISIBLE);

    in_settings.setOnClickListener(new OnClickListener() {

      @Override public void onClick(
        final @Nullable View v)
      {
        final FragmentManager fm = ReaderActivity.this.getFragmentManager();
        final ReaderSettingsDialog d = new ReaderSettingsDialog();
        d.show(fm, "settings-dialog");
      }
    });

    this.view_loading = in_loading;
    this.view_progress_text = in_progress_text;
    this.view_progress_bar = in_progress_bar;
    this.view_title_text = in_title_text;
    this.view_web_view = in_webview;
    this.view_hud = in_hud;
    this.view_toc = in_toc;
    this.view_settings = in_settings;
    this.web_view_resized = true;

    final WebViewClient wv_client = new WebViewClient() {
      @Override public void onLoadResource(
        final @Nullable WebView view,
        final @Nullable String url)
      {
        ReaderActivity.LOG.debug("web-request: {}", url);
      }

      @Override public boolean shouldOverrideUrlLoading(
        final @Nullable WebView view,
        final @Nullable String url)
      {
        final String nu = NullCheck.notNull(url);
        final URI uu = NullCheck.notNull(URI.create(nu));

        ReaderActivity.LOG.debug("should-intercept: {}", nu);

        if (nu.startsWith("simplified:")) {
          sd.dispatch(uu, ReaderActivity.this);
          return true;
        }

        if (nu.startsWith("readium:")) {
          rd.dispatch(uu, ReaderActivity.this);
          return true;
        }

        return super.shouldOverrideUrlLoading(view, url);
      }
    };
    in_webview.setBackgroundColor(0x00000000);
    in_webview.setWebViewClient(wv_client);
    in_webview.setOnLongClickListener(new OnLongClickListener() {
      @Override public boolean onLongClick(
        final @Nullable View v)
      {
        ReaderActivity.LOG.debug("ignoring long click on web view");
        return true;
      }
    });

    final WebSettings s = NullCheck.notNull(in_webview.getSettings());
    s.setAppCacheEnabled(false);
    s.setAllowFileAccess(false);
    s.setAllowFileAccessFromFileURLs(false);
    s.setAllowContentAccess(false);
    s.setCacheMode(WebSettings.LOAD_NO_CACHE);
    s.setGeolocationEnabled(false);
    s.setJavaScriptEnabled(true);

    this.readium_js_api = ReaderReadiumJavaScriptAPI.newAPI(in_webview);
    this.simplified_js_api = ReaderSimplifiedJavaScriptAPI.newAPI(in_webview);

    in_title_text.setText("");

    final ReaderReadiumEPUBLoaderType pl = rs.getEPUBLoader();
    pl.loadEPUB(epub_file, this);
  }

  @Override public void onCurrentPageError(
    final Throwable x)
  {
    ReaderActivity.LOG.error("{}", x.getMessage(), x);
  }

  @Override public void onCurrentPageReceived(
    final ReaderBookLocation l)
  {
    ReaderActivity.LOG.debug("received book location: {}", l);
  }

  @Override protected void onDestroy()
  {
    super.onDestroy();

    final SimplifiedReaderAppServicesType rs =
      Simplified.getReaderAppServices();

    final ReaderSettingsType settings = rs.getSettings();
    settings.removeListener(this);
  }

  @Override public void onEPUBLoadFailed(
    final Throwable x)
  {
    ErrorDialogUtilities.showErrorWithRunnable(
      this,
      ReaderActivity.LOG,
      "Could not load EPUB file",
      x,
      new Runnable() {
        @Override public void run()
        {
          ReaderActivity.this.finish();
        }
      });
  }

  @Override public void onEPUBLoadSucceeded(
    final Container c)
  {
    this.epub_container = c;
    final Package p = NullCheck.notNull(c.getDefaultPackage());

    final TextView in_title_text = NullCheck.notNull(this.view_title_text);
    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        in_title_text.setText(p.getTitle());
      }
    });

    /**
     * Configure the TOC button.
     */

    final SimplifiedReaderAppServicesType rs =
      Simplified.getReaderAppServices();

    final View in_toc = NullCheck.notNull(this.view_toc);

    if (rs.screenIsLarge()) {
      in_toc.setOnClickListener(new OnClickListener() {
        @Override public void onClick(
          final @Nullable View v)
        {
          ReaderActivity.LOG.debug("large screen TOC");
        }
      });
    } else {
      in_toc.setOnClickListener(new OnClickListener() {
        @Override public void onClick(
          final @Nullable View v)
        {
          ReaderActivity.LOG.debug("small screen TOC");

          final ReaderTOC sent_toc = ReaderTOC.fromPackage(p);
          ReaderTOCActivity.startActivityForResult(
            ReaderActivity.this,
            sent_toc);
          ReaderActivity.this.overridePendingTransition(0, 0);
        }
      });
    }

    /**
     * Get a reference to the web server. Start it if necessary (the callbacks
     * will still be executed if the server is already running).
     */

    final ReaderHTTPServerType hs = rs.getHTTPServer();
    hs.startIfNecessaryForPackage(p, this);
  }

  @Override public void onReaderSettingsChanged(
    final ReaderSettingsType s)
  {
    ReaderActivity.LOG.debug("reader settings changed");

    final ReaderReadiumJavaScriptAPIType js =
      NullCheck.notNull(this.readium_js_api);
    js.setPageStyleSettings(s);

    final ReaderColorScheme cs = s.getColorScheme();
    this.applyViewerColorScheme(cs);
  }

  @Override public void onReadiumFunctionDispatchError(
    final Throwable x)
  {
    ReaderActivity.LOG.error("{}", x.getMessage(), x);
  }

  @Override public void onReadiumFunctionInitialize()
  {
    ReaderActivity.LOG.debug("readium initialize requested");

    final SimplifiedReaderAppServicesType rs =
      Simplified.getReaderAppServices();

    final ReaderHTTPServerType hs = rs.getHTTPServer();
    final Container c = NullCheck.notNull(this.epub_container);
    final Package p = NullCheck.notNull(c.getDefaultPackage());
    p.setRootUrls(hs.getURIBase().toString(), null);

    final ReaderReadiumViewerSettings vs =
      NullCheck.notNull(this.viewer_settings);
    final ReaderReadiumJavaScriptAPIType js =
      NullCheck.notNull(this.readium_js_api);

    final OptionType<ReaderOpenPageRequestType> no_request = Option.none();
    js.openBook(p, vs, no_request);

    final WebView in_web_view = NullCheck.notNull(this.view_web_view);
    final ProgressBar in_loading = NullCheck.notNull(this.view_loading);
    final ProgressBar in_progress_bar =
      NullCheck.notNull(this.view_progress_bar);
    final TextView in_progress_text =
      NullCheck.notNull(this.view_progress_text);

    in_loading.setVisibility(View.GONE);
    in_web_view.setVisibility(View.VISIBLE);
    in_progress_bar.setVisibility(View.VISIBLE);
    in_progress_text.setVisibility(View.INVISIBLE);

    final ReaderSettingsType settings = rs.getSettings();
    this.onReaderSettingsChanged(settings);
  }

  @Override public void onReadiumFunctionInitializeError(
    final Throwable e)
  {
    ErrorDialogUtilities.showErrorWithRunnable(
      this,
      ReaderActivity.LOG,
      "Unable to initialize Readium",
      e,
      new Runnable() {
        @Override public void run()
        {
          ReaderActivity.this.finish();
        }
      });
  }

  /**
   * {@inheritDoc}
   *
   * When the device orientation changes, the configuration change handler
   * {@link #onConfigurationChanged(Configuration)} makes the web view
   * invisible so that the user does not see the now incorrectly-paginated
   * content. When Readium tells the app that the content pagination has
   * changed, it makes the web view visible again.
   */

  @Override public void onReadiumFunctionPaginationChanged(
    final ReaderPaginationChangedEvent e)
  {
    ReaderActivity.LOG.debug("pagination changed: {}", e);
    final WebView in_web_view = NullCheck.notNull(this.view_web_view);

    /**
     * Ask for Readium to deliver the unique identifier of the current page,
     * and tell Simplified that the page has changed and so any Javascript
     * state should be reconfigured.
     */

    final ReaderReadiumJavaScriptAPIType readium_js =
      NullCheck.notNull(this.readium_js_api);
    readium_js.getCurrentPage(this);

    /**
     * Configure the progress bar and text.
     */

    final TextView in_progress_text =
      NullCheck.notNull(this.view_progress_text);
    final ProgressBar in_progress_bar =
      NullCheck.notNull(this.view_progress_bar);
    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        final double p = e.getProgressFractional();
        in_progress_bar.setMax(100);
        in_progress_bar.setProgress((int) (100 * p));

        final List<OpenPage> pages = e.getOpenPages();
        if (pages.isEmpty()) {
          in_progress_text.setText("");
        } else {
          final OpenPage page = NullCheck.notNull(pages.get(0));
          in_progress_text.setText(NullCheck.notNull(String.format(
            "Page %d of %d",
            page.getSpineItemPageIndex() + 1,
            page.getSpineItemPageCount())));
        }
      }
    });

    final ReaderSimplifiedJavaScriptAPIType simplified_js =
      NullCheck.notNull(this.simplified_js_api);

    /**
     * Make the web view visible with a slight delay (as sometimes a
     * pagination-change event will be sent even though the content has not
     * yet been laid out in the web view). Only do this if the screen
     * orientation has just changed.
     */

    if (this.web_view_resized) {
      this.web_view_resized = false;
      UIThread.runOnUIThreadDelayed(new Runnable() {
        @Override public void run()
        {
          in_web_view.setVisibility(View.VISIBLE);
          in_progress_bar.setVisibility(View.VISIBLE);
          in_progress_text.setVisibility(View.VISIBLE);
          simplified_js.pageHasChanged();
        }
      }, 200);
    } else {
      UIThread.runOnUIThread(new Runnable() {
        @Override public void run()
        {
          simplified_js.pageHasChanged();
        }
      });
    }
  }

  @Override public void onReadiumFunctionPaginationChangedError(
    final Throwable x)
  {
    ReaderActivity.LOG.error("{}", x.getMessage(), x);
  }

  @Override public void onReadiumFunctionSettingsApplied()
  {
    ReaderActivity.LOG.debug("received settings applied");
  }

  @Override public void onReadiumFunctionSettingsAppliedError(
    final Throwable e)
  {
    ReaderActivity.LOG.error("{}", e.getMessage(), e);
  }

  @Override public void onReadiumFunctionUnknown(
    final String text)
  {
    ReaderActivity.LOG.error("unknown readium function: {}", text);
  }

  @Override public void onServerStartFailed(
    final ReaderHTTPServerType hs,
    final Throwable x)
  {
    ErrorDialogUtilities.showErrorWithRunnable(
      this,
      ReaderActivity.LOG,
      "Could not start http server",
      x,
      new Runnable() {
        @Override public void run()
        {
          ReaderActivity.this.finish();
        }
      });
  }

  @Override public void onServerStartSucceeded(
    final ReaderHTTPServerType hs,
    final boolean first)
  {
    if (first) {
      ReaderActivity.LOG.debug("http server started");
    } else {
      ReaderActivity.LOG.debug("http server already running");
    }

    this.makeInitialReadiumRequest(hs);
  }

  @Override public void onSimplifiedFunctionDispatchError(
    final Throwable x)
  {
    ReaderActivity.LOG.error("{}", x.getMessage(), x);
  }

  @Override public void onSimplifiedFunctionUnknown(
    final String text)
  {
    ReaderActivity.LOG.error("unknown function: {}", text);
  }

  @Override public void onSimplifiedGestureCenter()
  {
    final ViewGroup in_hud = NullCheck.notNull(this.view_hud);
    UIThread.runOnUIThread(new Runnable() {
      @Override public void run()
      {
        switch (in_hud.getVisibility()) {
          case View.VISIBLE:
          {
            FadeUtilities
              .fadeOut(in_hud, FadeUtilities.DEFAULT_FADE_DURATION);
            break;
          }
          case View.INVISIBLE:
          case View.GONE:
          {
            FadeUtilities.fadeIn(in_hud, FadeUtilities.DEFAULT_FADE_DURATION);
            break;
          }
        }
      }
    });
  }

  @Override public void onSimplifiedGestureCenterError(
    final Throwable x)
  {
    ReaderActivity.LOG.error("{}", x.getMessage(), x);
  }

  @Override public void onSimplifiedGestureLeft()
  {
    final ReaderReadiumJavaScriptAPIType js =
      NullCheck.notNull(this.readium_js_api);
    js.pagePrevious();
  }

  @Override public void onSimplifiedGestureLeftError(
    final Throwable x)
  {
    ReaderActivity.LOG.error("{}", x.getMessage(), x);
  }

  @Override public void onSimplifiedGestureRight()
  {
    final ReaderReadiumJavaScriptAPIType js =
      NullCheck.notNull(this.readium_js_api);
    js.pageNext();
  }

  @Override public void onSimplifiedGestureRightError(
    final Throwable x)
  {
    ReaderActivity.LOG.error("{}", x.getMessage(), x);
  }

  @Override public void onTOCSelectionReceived(
    final TOCElement e)
  {
    ReaderActivity.LOG.debug("received TOC selection: {}", e);

    final ReaderReadiumJavaScriptAPIType js =
      NullCheck.notNull(this.readium_js_api);

    js.openContentURL(e.getContentRef(), e.getSourceHref());
  }
}
