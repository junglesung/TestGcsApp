package com.vernonsung.testgcsapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.OpenableColumns;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends AppCompatActivity {
    // Log tag
    private final String LOGTAG = "Test";
    // The key to identify startActivityForResult()
    private final int START_ACTIVITY_ID_CHOOSE_AN_IMAGE = 1000;
    // Upload image URL
    private final String UPLOAD_IMAGE_URL = "https://testgcsserver.appspot.com/api/0.1/storeImage";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button buttonBrowseUpload = (Button)findViewById(R.id.buttonBrowseUpload);
        buttonBrowseUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uploadAndDownloadGcs(v);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void uploadAndDownloadGcs(View v) {
        // Browse a picture
        chooseAnImage();
    }

    public void chooseAnImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);

        // Filter to only show results that can be "opened", such as a
        // file (as opposed to a list of contacts or timezones)
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        // Filter to show only images, using the image MIME data type.
        // If one wanted to search for ogg vorbis files, the type would be "audio/ogg".
        // To search for all documents available via installed storage providers,
        // it would be "*/*".
        intent.setType("image/*");

        //第一個問題是Android系統找不到符合指定MIME類型的內容選取器，程式將會因為找不到可執行的Activity而直接閃退，這個問題甚至可能會沒辦法直接用try-catch來解決。第二個可能會遇到的問題是，當Android系統找到兩種以上可用的App或是Activity支援指定的MIME類型時，可能會自動使用其中一種，此時也許就會選到無法正常使用的App或是Activity，連帶使我們的App永遠無法正常使用。
        //要解決第一個找不到Activity的問題，可以事先使用PackageManager查詢可以使用該MIME類型的Activity列表來解決。而要解決第二個可用App或是Activity有兩個以上的問題的話，可以使用系統內建的Intent Chooser，跳出選單讓使用者選擇要使用哪個。
        PackageManager packageManager = this.getPackageManager();
        List<ResolveInfo> list = packageManager.queryIntentActivities(intent, PackageManager.GET_ACTIVITIES);
        if (list.size() > 0) {
            // 如果有可用的Activity
            // 使用Intent Chooser
            Intent destIntent = Intent.createChooser(intent, "選取圖片");
            startActivityForResult(destIntent, START_ACTIVITY_ID_CHOOSE_AN_IMAGE);
        } else {
            // 沒有可用的Activity
            Toast.makeText(this, getString(R.string.no_app_to_choose_an_image), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // The ACTION_GET_CONTENT intent was sent with the request code
        // START_ACTIVITY_ID_CHOOSE_AN_IMAGE. If the request code seen here doesn't match, it's the
        // response to some other intent, and the code below shouldn't run at all.
        if (requestCode == START_ACTIVITY_ID_CHOOSE_AN_IMAGE && resultCode == Activity.RESULT_OK) {
            // The document selected by the user won't be returned in the intent.
            // Instead, a URI to that document will be contained in the return intent
            // provided to this method as a parameter.
            // Pull that URI using resultData.getData().
            if (data != null) {
                Uri uri = data.getData();
                Log.i(LOGTAG, "Chosen image URI " + uri.toString());

                // Check network connection ability and then access Google Cloud Storage
                ConnectivityManager connMgr = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
                if (networkInfo != null && networkInfo.isConnected()) {
                    new UploadDownloadImageTask().execute(uri);
                } else {
                    Toast.makeText(this, "No network connection available.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * Implementation of AsyncTask, to fetch the data in the background away from
     * the UI thread.
     */
    private class UploadDownloadImageTask extends AsyncTask<Uri, Void, Bitmap> {

        @Override
        protected Bitmap doInBackground(Uri... uris) {
            // Deal with one image at a time
            if (uris.length != 1) {
                Log.e(LOGTAG, "No specified URI");
                return null;
            }
            try {
                // Upload
                String url = sendAnImage(uris[0]);
                if (url == null) {
                    Log.e(LOGTAG, "Upload the image failed");
                    return null;
                }
                Log.i(LOGTAG, "Image URL " + url);
                // Download
                return loadFromNetwork(url);
            } catch (IOException e) {
                Log.e(LOGTAG, getString(R.string.connection_error));
                return null;
            }
        }

        /**
         * Uses the logging framework to display the output of the fetch
         * operation in the log fragment.
         */
        @Override
        protected void onPostExecute(Bitmap bitmap) {
            ImageView imageView = (ImageView) findViewById(R.id.imageView);
            imageView.setImageBitmap(bitmap);
        }
    }

    // Send an image to Google Cloud Storage
    // Return the URL of the uploaded image
    // Return null if failed
    private String sendAnImage(Uri uri) throws IOException {
        URL url = new URL(UPLOAD_IMAGE_URL);
        HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
        int size;
        OutputStream out;
        InputStream in = null;
        String imageUrl = null;

        // Set content type
        urlConnection.setRequestProperty("Content-Type", "image/jpeg");

        try {
            // To upload data to a web server, configure the connection for output using setDoOutput(true). It will use POST if setDoOutput(true) has been called.
            urlConnection.setDoOutput(true);

            // For best performance, you should call either setFixedLengthStreamingMode(int) when the body length is known in advance, or setChunkedStreamingMode(int) when it is not. Otherwise HttpURLConnection will be forced to buffer the complete request body in memory before it is transmitted, wasting (and possibly exhausting) heap and increasing latency.
            size = fetchUriSize(uri);
            if (size > 0) {
                urlConnection.setFixedLengthStreamingMode(size);
            } else {
                // Set default chunk size
                urlConnection.setChunkedStreamingMode(0);
            }

            // Get the OutputStream of HTTP client
            out = new BufferedOutputStream(urlConnection.getOutputStream());
            // Get the InputStream of the file
            in = new BufferedInputStream(getContentResolver().openInputStream(uri));
            // Copy from file to the HTTP client
            int byte_;
            while ((byte_ = in.read()) != -1) {
                out.write(byte_);
            }
            // Make sure to close streams, otherwise "unexpected end of stream" error will happen
            out.close();
            in.close();
            in = null;

            // Set timeout
            urlConnection.setReadTimeout(10000 /* milliseconds */);
            urlConnection.setConnectTimeout(15000 /* milliseconds */);

            // Send and get response
            // getResponseCode() will automatically trigger connect()
            int responseCode = urlConnection.getResponseCode();
            String responseMsg = urlConnection.getResponseMessage();
            Log.d(LOGTAG, "Response " + responseCode + " " + responseMsg);
            if (responseCode != HttpURLConnection.HTTP_CREATED) {
                return null;
            }

            // Get image URL
            imageUrl = urlConnection.getHeaderField("Location");
            Log.d(LOGTAG, "Image URL " + imageUrl);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            urlConnection.disconnect();
            if (in != null)
                in.close();
        }
        return imageUrl;
    }

    // Get file size to set HTTP POST body length
    private int fetchUriSize(Uri uri) {
        int size = -1;

        // The query, since it only applies to a single document, will only return
        // one row. There's no need to filter, sort, or select fields, since we want
        // all fields for one document.
        Cursor cursor = this.getContentResolver().query(uri, null, null, null, null, null);

        try {
            // moveToFirst() returns false if the cursor has 0 rows.  Very handy for
            // "if there's anything to look at, look at it" conditionals.
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                // If the size is unknown, the value stored is null.  But since an
                // int can't be null in Java, the behavior is implementation-specific,
                // which is just a fancy term for "unpredictable".  So as
                // a rule, check if it's null before assigning to an int.  This will
                // happen often:  The storage API allows for remote files, whose
                // size might not be locally known.
                if (!cursor.isNull(sizeIndex)) {
                    // Technically the column stores an int, but cursor.getString()
                    // will do the conversion automatically.
                    size = cursor.getInt(sizeIndex);
                }
                Log.i(LOGTAG, "Image size " + size + " byte(s)");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return size;
    }

    /** Initiates the fetch operation. */
    private Bitmap loadFromNetwork(String urlString) throws IOException {
        InputStream stream = null;
        Bitmap bitmap = null;

        try {
            stream = downloadUrl(urlString);
            bitmap =  BitmapFactory.decodeStream(stream);
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
        return bitmap;
    }

    /**
     * Given a string representation of a URL, sets up a connection and gets
     * an input stream.
     * @param urlString A string representation of a URL.
     * @return An InputStream retrieved from a successful HttpURLConnection.
     * @throws java.io.IOException
     */
    private InputStream downloadUrl(String urlString) throws IOException {
        // BEGIN_INCLUDE(get_inputstream)
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setReadTimeout(10000 /* milliseconds */);
        conn.setConnectTimeout(15000 /* milliseconds */);
        conn.setRequestMethod("GET");
        // Sets the flag indicating whether this URLConnection allows input. It cannot be set after the connection is established.
        conn.setDoInput(true);
        // Start the query
        conn.connect();
        return conn.getInputStream();
        // END_INCLUDE(get_inputstream)
    }
}
