/*
    Copyright (C) 2014 Sergii Pylypenko.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.cups.android;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;
import android.text.Editable;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.content.res.Configuration;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.view.View.OnKeyListener;
import android.view.MenuItem;
import android.view.Menu;
import android.view.Gravity;
import android.text.method.TextKeyListener;
import java.util.LinkedList;
import java.io.SequenceInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.zip.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.Set;
import android.text.SpannedString;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.InputStreamReader;
import android.view.inputmethod.InputMethodManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import java.util.concurrent.Semaphore;
import android.content.pm.ActivityInfo;
import android.view.Display;
import android.text.InputType;
import android.util.Log;
import android.view.Surface;
import android.app.ProgressDialog;
import android.printservice.*;
import android.print.*;
import java.util.*;
import java.io.*;
import android.os.Environment;
import android.os.StatFs;
import java.net.URL;

public class Installer
{
	static boolean unpacking = false;

	synchronized static boolean isInstalled(Context p)
	{
		return new File(p.getFilesDir().getAbsolutePath() + Cups.IMG + Cups.CUPSD).exists();
	}

	synchronized static void unpackData(final MainActivity p, final TextView text)
	{
		unpacking = true;
		new Thread(new Runnable()
		{
			public void run()
			{
				unpackDataThread(p, text);
			}
		}).start();
	}

	synchronized static void unpackDataThread(final MainActivity p, final TextView text)
	{
		if (isInstalled(p))
		{
			unpacking = false;
			Cups.startCupsDaemon(p);
			p.enableSettingsButton();
			return;
		}

		Log.i(TAG, "Extracting CUPS data");

		setText(p, text, p.getResources().getString(R.string.please_wait_unpack));

		StatFs storage = new StatFs(p.getFilesDir().getPath());
		long avail = (long)storage.getAvailableBlocks() * storage.getBlockSize() / 1024 / 1024;
		long needed = 600;
		Log.i(TAG, "Available free space: " + avail + " Mb required: " + needed + " Mb");
		if (avail < needed)
		{
			setText(p, text, p.getResources().getString(R.string.not_enough_space, needed, avail));
			return;
		}

		try
		{
			p.getFilesDir().mkdirs();
			InputStream stream = p.getAssets().open("busybox-" + android.os.Build.CPU_ABI);
			String busybox = new File(p.getFilesDir(), "busybox").getAbsolutePath();
			OutputStream out = new FileOutputStream(busybox);

			Cups.copyStream(stream, out);

			new Proc(new String[] {"/system/bin/chmod", "0755", busybox}, p.getFilesDir());

			try
			{
				InputStream archiveAssets = p.getAssets().open("dist-cups-wheezy.tar.xz");
				Process proc = Runtime.getRuntime().exec(new String[] {busybox, "tar", "xJ"}, null, p.getFilesDir());
				Cups.copyStream(archiveAssets, proc.getOutputStream());
				int status = proc.waitFor();
				Log.i(TAG, "Unpacking data from assets: status: " + status);
			}
			catch(Exception e)
			{
				Log.i(TAG, "Error unpacking data from assets: " + e.toString());
				Log.i(TAG, "No data archive in assets, trying OBB data");
				try
				{
					File obbFile = new File(p.getExternalFilesDir(null).getParentFile().getParentFile().getParentFile(),
												"obb/" + p.getPackageName() + "/main.100." + p.getPackageName() + ".obb");
					if (!obbFile.exists())
						throw new IOException("Cannot find data file: " + obbFile.getAbsolutePath());
					new Proc(new String[] {busybox, "tar", "xJf",
								obbFile.getAbsolutePath()},
								p.getFilesDir());
				}
				catch(Exception ee)
				{
					final String ARCHIVE_URL = "http://sourceforge.net/projects/libsdl-android/files/ubuntu/dist-cups-wheezy.tar.xz/download";
					Log.i(TAG, "Error unpacking data from OBB: " + e.toString());
					Log.i(TAG, "No data archive in OBB, downloading from web: " + ARCHIVE_URL);
					setText(p, text, p.getResources().getString(R.string.downloading_web));
					URL link = new URL(ARCHIVE_URL);
					InputStream download = new BufferedInputStream(link.openStream());
					Process proc = Runtime.getRuntime().exec(new String[] {busybox, "tar", "xJ"}, null, p.getFilesDir());
					Cups.copyStream(download, proc.getOutputStream());
					int status = proc.waitFor();
					Log.i(TAG, "Downloading from web: status: " + status);
				}
			}

			new Proc(new String[] {busybox, "cp", "-af", "img-" + android.os.Build.CPU_ABI + "/.", "img/"}, p.getFilesDir());
			new Proc(new String[] {busybox, "rm", "-rf", "img-armeabi-v7a", "img-x86"}, p.getFilesDir());
			stream = p.getAssets().open("cupsd.conf");
			out = new FileOutputStream(new File(Cups.chrootPath(p), "etc/cups/cupsd.conf"));
			Cups.copyStream(stream, out);

			Log.i(TAG, "Extracting data finished");
		}
		catch(Exception e)
		{
			Log.i(TAG, "Error extracting data: " + e.toString());
			setText(p, text, p.getResources().getString(R.string.error_extracting) + " " + e.toString());
			return;
		}

		unpacking = false;
		p.enableSettingsButton();
		Cups.startCupsDaemon(p);
	}

	static void setText(final Activity p, final TextView text, final String str)
	{
		p.runOnUiThread(new Runnable()
		{
			public void run()
			{
				text.setText(str);
			}
		});
	}

	static final String TAG = "Installer";
}