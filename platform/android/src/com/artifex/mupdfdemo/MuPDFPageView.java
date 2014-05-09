package com.artifex.mupdfdemo;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;



/* This enum should be kept in line with the cooresponding C enum in mupdf.c */
enum SignatureState {
	NoSupport,
	Unsigned,
	Signed
}

abstract class PassClickResultVisitor {
	public abstract void visitText(PassClickResultText result);
	public abstract void visitChoice(PassClickResultChoice result);
	public abstract void visitSignature(PassClickResultSignature result);
}

class PassClickResult {
	public final boolean changed;

	public PassClickResult(boolean _changed) {
		changed = _changed;
	}

	public void acceptVisitor(PassClickResultVisitor visitor) {
	}
}

class PassClickResultText extends PassClickResult {
	public final String text;

	public PassClickResultText(boolean _changed, String _text) {
		super(_changed);
		text = _text;
	}

	public void acceptVisitor(PassClickResultVisitor visitor) {
		visitor.visitText(this);
	}
}

class PassClickResultChoice extends PassClickResult {
	public final String [] options;
	public final String [] selected;

	public PassClickResultChoice(boolean _changed, String [] _options, String [] _selected) {
		super(_changed);
		options = _options;
		selected = _selected;
	}

	public void acceptVisitor(PassClickResultVisitor visitor) {
		visitor.visitChoice(this);
	}
}

class PassClickResultSignature extends PassClickResult {
	public final SignatureState state;

	public PassClickResultSignature(boolean _changed, int _state) {
		super(_changed);
		state = SignatureState.values()[_state];
	}

	public void acceptVisitor(PassClickResultVisitor visitor) {
		visitor.visitSignature(this);
	}
}

public class MuPDFPageView extends PageView implements MuPDFView {
        private int lastHitAnnotation = 0;
    
	final private FilePicker.FilePickerSupport mFilePickerSupport;
	private final MuPDFCore mCore;
	private AsyncTask<Void,Void,PassClickResult> mPassClick;
	private RectF mWidgetAreas[];
	private Annotation mAnnotations[];
	private int mSelectedAnnotationIndex = -1;
	private AsyncTask<Void,Void,RectF[]> mLoadWidgetAreas;
	private AsyncTask<Void,Void,Annotation[]> mLoadAnnotations;
	private AlertDialog.Builder mTextEntryBuilder;
	private AlertDialog.Builder mChoiceEntryBuilder;
	private AlertDialog.Builder mSigningDialogBuilder;
	private AlertDialog.Builder mSignatureReportBuilder;
	private AlertDialog.Builder mPasswordEntryBuilder;
	private EditText mPasswordText;
	private AlertDialog mTextEntry;
	private AlertDialog mPasswordEntry;
	private EditText mEditText;
	private AsyncTask<String,Void,Boolean> mSetWidgetText;
	private AsyncTask<String,Void,Void> mSetWidgetChoice;
	private AsyncTask<PointF[],Void,Void> mAddNonInkAnnotation;
	private AsyncTask<PointF[][],Void,Void> mAddInk;
	private AsyncTask<Integer,Void,Void> mDeleteAnnotation;
	private AsyncTask<Void,Void,String> mCheckSignature;
	private AsyncTask<Void,Void,Boolean> mSign;
	private Runnable changeReporter;

	public MuPDFPageView(Context c, FilePicker.FilePickerSupport filePickerSupport, MuPDFCore core, Point parentSize, Bitmap sharedHqBm) {
		super(c, parentSize, sharedHqBm);
		mFilePickerSupport = filePickerSupport;
		mCore = core;
		mTextEntryBuilder = new AlertDialog.Builder(c);
		mTextEntryBuilder.setTitle(getContext().getString(R.string.fill_out_text_field));
		LayoutInflater inflater = (LayoutInflater)c.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		mEditText = (EditText)inflater.inflate(R.layout.textentry, null);
		mTextEntryBuilder.setView(mEditText);
		mTextEntryBuilder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		mTextEntryBuilder.setPositiveButton(R.string.okay, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				mSetWidgetText = new AsyncTask<String,Void,Boolean> () {
					@Override
					protected Boolean doInBackground(String... arg0) {
						return mCore.setFocusedWidgetText(mPageNumber, arg0[0]);
					}
					@Override
					protected void onPostExecute(Boolean result) {
						changeReporter.run();
						if (!result)
							invokeTextDialog(mEditText.getText().toString());
					}
				};

				mSetWidgetText.execute(mEditText.getText().toString());
			}
		});
		mTextEntry = mTextEntryBuilder.create();

		mChoiceEntryBuilder = new AlertDialog.Builder(c);
		mChoiceEntryBuilder.setTitle(getContext().getString(R.string.choose_value));

		mSigningDialogBuilder = new AlertDialog.Builder(c);
		mSigningDialogBuilder.setTitle("Select certificate and sign?");
		mSigningDialogBuilder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		mSigningDialogBuilder.setPositiveButton(R.string.okay, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				FilePicker picker = new FilePicker(mFilePickerSupport) {
					@Override
					void onPick(Uri uri) {
						signWithKeyFile(uri);
					}
				};

				picker.pick();
			}
		});

		mSignatureReportBuilder = new AlertDialog.Builder(c);
		mSignatureReportBuilder.setTitle("Signature checked");
		mSignatureReportBuilder.setPositiveButton(R.string.okay, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});

		mPasswordText = new EditText(c);
		mPasswordText.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
		mPasswordText.setTransformationMethod(new PasswordTransformationMethod());

		mPasswordEntryBuilder = new AlertDialog.Builder(c);
		mPasswordEntryBuilder.setTitle(R.string.enter_password);
		mPasswordEntryBuilder.setView(mPasswordText);
		mPasswordEntryBuilder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});

		mPasswordEntry = mPasswordEntryBuilder.create();
	}

	private void signWithKeyFile(final Uri uri) {
		mPasswordEntry.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
		mPasswordEntry.setButton(AlertDialog.BUTTON_POSITIVE, "Sign", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
				signWithKeyFileAndPassword(uri, mPasswordText.getText().toString());
			}
		});

		mPasswordEntry.show();
	}

	private void signWithKeyFileAndPassword(final Uri uri, final String password) {
		mSign = new AsyncTask<Void,Void,Boolean>() {
			@Override
			protected Boolean doInBackground(Void... params) {
				return mCore.signFocusedSignature(Uri.decode(uri.getEncodedPath()), password);
			}
			@Override
			protected void onPostExecute(Boolean result) {
				if (result)
				{
					changeReporter.run();
				}
				else
				{
					mPasswordText.setText("");
					signWithKeyFile(uri);
				}
			}

		};

		mSign.execute();
	}

	public LinkInfo hitLink(float x, float y) {
		float scale = mSourceScale*(float)getWidth()/(float)mSize.x;
		float docRelX = (x - getLeft())/scale;
		float docRelY = (y - getTop())/scale;

		for (LinkInfo l: mLinks)
			if (l.rect.contains(docRelX, docRelY))
				return l;

		return null;
	}

	private void invokeTextDialog(String text) {
		mEditText.setText(text);
		mTextEntry.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
		mTextEntry.show();
	}

	private void invokeChoiceDialog(final String [] options) {
		mChoiceEntryBuilder.setItems(options, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				mSetWidgetChoice = new AsyncTask<String,Void,Void>() {
					@Override
					protected Void doInBackground(String... params) {
						String [] sel = {params[0]};
						mCore.setFocusedWidgetChoiceSelected(sel);
						return null;
					}

					@Override
					protected void onPostExecute(Void result) {
						changeReporter.run();
					}
				};

				mSetWidgetChoice.execute(options[which]);
			}
		});
		AlertDialog dialog = mChoiceEntryBuilder.create();
		dialog.show();
	}

	private void invokeSignatureCheckingDialog() {
		mCheckSignature = new AsyncTask<Void,Void,String> () {
			@Override
			protected String doInBackground(Void... params) {
				return mCore.checkFocusedSignature();
			}
			@Override
			protected void onPostExecute(String result) {
				AlertDialog report = mSignatureReportBuilder.create();
				report.setMessage(result);
				report.show();
			}
		};

		mCheckSignature.execute();
	}

	private void invokeSigningDialog() {
		AlertDialog dialog = mSigningDialogBuilder.create();
		dialog.show();
	}

	private void warnNoSignatureSupport() {
		AlertDialog dialog = mSignatureReportBuilder.create();
		dialog.setTitle("App built with no signature support");
		dialog.show();
	}

	public void setChangeReporter(Runnable reporter) {
		changeReporter = reporter;
	}

	public Hit passClickEvent(float x, float y) {
		float scale = mSourceScale*(float)getWidth()/(float)mSize.x;
		final float docRelX = (x - getLeft())/scale;
		final float docRelY = (y - getTop())/scale;
		boolean hit = false;
		int i;

		if (mLinks != null)
                    for (LinkInfo l: mLinks)
                        if (l.rect.contains(docRelX, docRelY))
                        {
                            deselectAnnotation();
                            switch(l.type())
                            {
                                case Internal:
                                    return Hit.LinkInternal;
                                case External:
                                    return Hit.LinkExternal;
                                case Remote:
                                    return Hit.LinkRemote;
                            }
                        }
                
		if (mAnnotations != null) {
                    for (i = 0; i < mAnnotations.length; i++)
                    {
                            //If multiple annotations overlap, make sure we
                            //return a different annotation as hit each
                            //time we are called 
                        int j = (i+lastHitAnnotation+1) % mAnnotations.length;
                        if (mAnnotations[j].contains(docRelX, docRelY))
                        {
                            hit = true;
                            i = lastHitAnnotation = j;
                            break;
                        }
                    }
                    if (hit) {
                        switch (mAnnotations[i].type) {
                            case HIGHLIGHT:
                            case UNDERLINE:
                            case SQUIGGLY:
                            case STRIKEOUT:
                            case INK:
                                mSelectedAnnotationIndex = i;
                                setItemSelectBox(mAnnotations[i]);
                                return Hit.Annotation;
                        }
                    }
		}
                deselectAnnotation();
                
                if (!MuPDFCore.javascriptSupported())
                    return Hit.Nothing;

		if (mWidgetAreas != null) {
			for (i = 0; i < mWidgetAreas.length && !hit; i++)
				if (mWidgetAreas[i].contains(docRelX, docRelY))
					hit = true;
		}

		if (hit) {
			mPassClick = new AsyncTask<Void,Void,PassClickResult>() {
				@Override
				protected PassClickResult doInBackground(Void... arg0) {
					return mCore.passClickEvent(mPageNumber, docRelX, docRelY);
				}

				@Override
				protected void onPostExecute(PassClickResult result) {
					if (result.changed) {
						changeReporter.run();
					}

					result.acceptVisitor(new PassClickResultVisitor() {
						@Override
						public void visitText(PassClickResultText result) {
							invokeTextDialog(result.text);
						}

						@Override
						public void visitChoice(PassClickResultChoice result) {
							invokeChoiceDialog(result.options);
						}

						@Override
						public void visitSignature(PassClickResultSignature result) {
							switch (result.state) {
							case NoSupport:
								warnNoSignatureSupport();
								break;
							case Unsigned:
								invokeSigningDialog();
								break;
							case Signed:
								invokeSignatureCheckingDialog();
								break;
							}
						}
					});
				}
			};

			mPassClick.execute();
			return Hit.Widget;
		}

		return Hit.Nothing;
	}

	public boolean copySelection() {
		final StringBuilder text = new StringBuilder();

		processSelectedText(new TextProcessor() {
			StringBuilder line;

			public void onStartLine() {
				line = new StringBuilder();
			}

			public void onWord(TextWord word) {
				if (line.length() > 0)
					line.append(' ');
				line.append(word.w);
			}

			public void onEndLine() {
				if (text.length() > 0)
					text.append('\n');
				text.append(line);
			}

                        public void onEndText() {};
		});

		if (text.length() == 0)
			return false;

		int currentApiVersion = android.os.Build.VERSION.SDK_INT;
		if (currentApiVersion >= android.os.Build.VERSION_CODES.HONEYCOMB) {
			android.content.ClipboardManager cm = (android.content.ClipboardManager)mContext.getSystemService(Context.CLIPBOARD_SERVICE);

			cm.setPrimaryClip(ClipData.newPlainText("MuPDF", text));
		} else {
			android.text.ClipboardManager cm = (android.text.ClipboardManager)mContext.getSystemService(Context.CLIPBOARD_SERVICE);
			cm.setText(text);
		}

		deselectText();

		return true;
	}

	public boolean markupSelection(final Annotation.Type type) {
		final ArrayList<PointF> quadPoints = new ArrayList<PointF>();
		processSelectedText(new TextProcessor() {
			RectF rect;

			public void onStartLine() {
				rect = new RectF();
			}

			public void onWord(TextWord word) {
				rect.union(word);
			}

			public void onEndLine() {
				if (!rect.isEmpty()) {
                                        quadPoints.add(new PointF(rect.left, rect.bottom));
                                        quadPoints.add(new PointF(rect.right, rect.bottom));
					quadPoints.add(new PointF(rect.right, rect.top));
					quadPoints.add(new PointF(rect.left, rect.top));
				}
			}
                        
                        public void onEndText() {};
		});

		if (quadPoints.size() == 0)
			return false;

		mAddNonInkAnnotation = new AsyncTask<PointF[],Void,Void>() {
			@Override
			protected Void doInBackground(PointF[]... params) {
				addMarkup(params[0], type);
				return null;
			}

			@Override
			protected void onPostExecute(Void result) {
				loadAnnotations();
				update();
			}
		};

		mAddNonInkAnnotation.execute(quadPoints.toArray(new PointF[quadPoints.size()]));

		deselectText();

		return true;
	}
    
    public void deleteSelectedAnnotation() {
        if (mSelectedAnnotationIndex != -1) {
			if (mDeleteAnnotation != null)
				mDeleteAnnotation.cancel(true);

			mDeleteAnnotation = new AsyncTask<Integer,Void,Void>() {
				@Override
				protected Void doInBackground(Integer... params) {
					mCore.deleteAnnotation(mPageNumber, params[0]);
					return null;
				}

				@Override
				protected void onPostExecute(Void result) {
					loadAnnotations();
					update();
				}
			};

			mDeleteAnnotation.execute(mSelectedAnnotationIndex);

                        deselectAnnotation();
		}
	}


    public void editSelectedAnnotation() {
        if (mSelectedAnnotationIndex != -1) {
            PointF[][] arcs = mAnnotations[mSelectedAnnotationIndex].arcs;
            if(arcs != null)
            {
                ArrayList<ArrayList<PointF>> drawing = new ArrayList<ArrayList<PointF>>();
                for(int i = 0; i < arcs.length; i++)
                {
                    drawing.add(new ArrayList<PointF>(Arrays.asList(arcs[i])));
                }
                Log.i("PageView", "drawing="+drawing);
                setmDrawing(drawing);
                deleteSelectedAnnotation();
//                deselectAnnotation();
            }
        }
    }
    
    
    public void deselectAnnotation() {
		mSelectedAnnotationIndex = -1;
		setItemSelectBox(null);
	}

    public boolean saveDraw() { 
		PointF[][] path = getDraw();

		if (path == null)
			return false;

		if (mAddInk != null) {
			mAddInk.cancel(true);
			mAddInk = null;
		}
		mAddInk = new AsyncTask<PointF[][],Void,Void>() {
			@Override
			protected Void doInBackground(PointF[][]... params) {
				mCore.addInkAnnotation(mPageNumber, params[0]);
				return null;
			}

			@Override
			protected void onPostExecute(Void result) {
                            loadAnnotations();
                            update();
                            cancelDraw();
			}

		};
//		mAddInk.execute(getDraw());
                mAddInk.execute(path);
//		cancelDraw();
		return true;
	}

	@Override
	protected void drawPage(Bitmap bm, int sizeX, int sizeY,
			int patchX, int patchY, int patchWidth, int patchHeight) {
		mCore.drawPage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight);
	}

	@Override
	protected void updatePage(Bitmap bm, int sizeX, int sizeY,
			int patchX, int patchY, int patchWidth, int patchHeight) {
		mCore.updatePage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight);
	}

	@Override
	protected LinkInfo[] getLinkInfo() {
		return mCore.getPageLinks(mPageNumber);
	}

	@Override
	protected TextWord[][] getText() {
		return mCore.textLines(mPageNumber);
	}

	@Override
	protected void addMarkup(PointF[] quadPoints, Annotation.Type type) {
		mCore.addMarkupAnnotation(mPageNumber, quadPoints, type);
	}

	private void loadAnnotations() {
		mAnnotations = null;
		if (mLoadAnnotations != null) mLoadAnnotations.cancel(true);
		mLoadAnnotations = new AsyncTask<Void,Void,Annotation[]> () {
			@Override
			protected Annotation[] doInBackground(Void... params) {
				return mCore.getAnnoations(mPageNumber);
			}

			@Override
			protected void onPostExecute(Annotation[] result) {
				mAnnotations = result;
			}
		};
		mLoadAnnotations.execute();
	}

	@Override
	public void setPage(final int page, PointF size) {

		mLoadWidgetAreas = new AsyncTask<Void,Void,RectF[]> () {
			@Override
			protected RectF[] doInBackground(Void... arg0) {
				return mCore.getWidgetAreas(page);
			}

			@Override
			protected void onPostExecute(RectF[] result) {
				mWidgetAreas = result;
			}
		};

		mLoadWidgetAreas.execute();

		super.setPage(page, size);
                loadAnnotations();//Must be done after super.setPage() otherwise page number is wrong!
	}

	public void setScale(float scale) {
		// This type of view scales automatically to fit the size
		// determined by the parent view groups during layout
	}

	@Override
	public void releaseResources() {
		if (mPassClick != null) {
			mPassClick.cancel(true);
			mPassClick = null;
		}

		if (mLoadWidgetAreas != null) {
			mLoadWidgetAreas.cancel(true);
			mLoadWidgetAreas = null;
		}

		if (mLoadAnnotations != null) {
			mLoadAnnotations.cancel(true);
			mLoadAnnotations = null;
		}

		if (mSetWidgetText != null) {
			mSetWidgetText.cancel(true);
			mSetWidgetText = null;
		}

		if (mSetWidgetChoice != null) {
			mSetWidgetChoice.cancel(true);
			mSetWidgetChoice = null;
		}

		if (mAddNonInkAnnotation != null) {
			mAddNonInkAnnotation.cancel(true);
			mAddNonInkAnnotation = null;
		}

		if (mDeleteAnnotation != null) {
			mDeleteAnnotation.cancel(true);
			mDeleteAnnotation = null;
		}

		super.releaseResources();
	}
}
