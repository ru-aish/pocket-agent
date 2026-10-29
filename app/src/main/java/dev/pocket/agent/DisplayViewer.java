package dev.pocket.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-demand local viewer. It never allocates displays or injects input. */
final class DisplayViewer implements AutoCloseable {
    private final Activity activity;
    private final Supplier<JSONObject> state;
    private final AgentDisplayCoordinator displays;
    private final Handler main=new Handler(Looper.getMainLooper());
    // Serialize attach/detach, including an attach that finishes after dismissal.
    private final ExecutorService io=Executors.newSingleThreadExecutor(task->new Thread(task,"PocketDisplayViewer"));
    private AlertDialog picker,preview;
    private LinearLayout rows;
    private TextView previewStatus;
    private SurfaceView surface;
    private FrameLayout viewport;
    private AgentDisplayCoordinator.AvailableDisplay selected;
    private Attachment attachment;
    private boolean closed,refreshPending;
    private int revision;
    private String renderedRows;
    private final Runnable refresh=this::refresh;

    private static final class Attachment {
        final Binder token=new Binder();
        final int displayId;
        // Accessed only by the serial I/O executor.
        ShizukuDisplayClient client;
        Attachment(int displayId) {this.displayId=displayId;}
    }

    DisplayViewer(Activity activity,Supplier<JSONObject> state) {
        this.activity=activity;this.state=state;
        displays=AgentDisplayCoordinator.shared(activity);
    }

    void show() {
        if(closed||activity.isFinishing())return;
        // The bootstrap activity also runs on worker displays. Never preview a
        // worker display inside itself, or inside another worker's workspace.
        if(activity.getDisplay()==null||activity.getDisplay().getDisplayId()!=Display.DEFAULT_DISPLAY)return;
        dismiss();
        rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16),dp(8),dp(16),dp(8));
        rows.addView(text("Checking available displays…"));
        ScrollView scroll=new ScrollView(activity);scroll.addView(rows);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Displays").setView(scroll).setNegativeButton("Close",null).create();
        picker=dialog;
        dialog.setOnDismissListener(ignored->{if(picker==dialog){picker=null;stopRefreshIfHidden();}});
        renderedRows=null;
        picker.show();
        refresh();
    }

    private boolean visible() {return picker!=null||preview!=null;}
    private void stopRefreshIfHidden() {
        if(!visible()) {revision++;main.removeCallbacks(refresh);}
    }
    private void refresh() {
        main.removeCallbacks(refresh);
        if(closed||!visible()||refreshPending)return;
        refreshPending=true;
        int request=revision;
        io.execute(()->{
            List<AgentDisplayCoordinator.AvailableDisplay> available=displays.availableDisplays();
            main.post(()->{
                refreshPending=false;
                if(closed||!visible())return;
                if(request==revision)render(available);
                main.postDelayed(refresh,1000);
            });
        });
    }

    private void render(List<AgentDisplayCoordinator.AvailableDisplay> available) {
        if(picker!=null) {
            StringBuilder key=new StringBuilder();
            for(AgentDisplayCoordinator.AvailableDisplay item:available)
                key.append(item.id).append(':').append(System.identityHashCode(item.generation)).append(':').append(label(item)).append('\n');
            if(!key.toString().equals(renderedRows)) {
                renderedRows=key.toString();rows.removeAllViews();
                if(available.isEmpty())rows.addView(text("No virtual displays available.\nDisplays appear while an agent uses a screen; tasks can also work without one."));
                for(AgentDisplayCoordinator.AvailableDisplay item:available) {
                    Button row=new Button(activity);row.setAllCaps(false);row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
                    row.setText(label(item));row.setEnabled(item.viewable());
                    row.setOnClickListener(ignored->open(item));rows.addView(row);
                }
            }
        }
        if(preview!=null&&selected!=null) {
            AgentDisplayCoordinator.AvailableDisplay current=null;
            for(AgentDisplayCoordinator.AvailableDisplay item:available)
                if(item.id==selected.id&&item.owner.equals(selected.owner)&&item.generation==selected.generation)current=item;
            if(current==null||!current.viewable()) {
                endAttachment();surface.setVisibility(View.GONE);
                previewStatus.setText(current==null?"Display closed · the task released its screen.":"Display unavailable · "+current.state);
            } else if(surface.getVisibility()==View.VISIBLE) {
                previewStatus.setText(label(current)+"\nLive · View only");
                sizeSurface();
            }
        }
    }

    private String label(AgentDisplayCoordinator.AvailableDisplay display) {
        JSONObject snapshot=state.get();
        String taskLabel,taskStatus;
        if("main".equals(display.owner)) {
            taskLabel="Main conversation";
            taskStatus=snapshot.optBoolean("busy")?"Working":"Idle";
        } else {
            JSONObject task=null;
            JSONArray tasks=snapshot.optJSONArray("tasks");
            if(tasks!=null)for(int i=0;i<tasks.length();i++) {
                JSONObject candidate=tasks.optJSONObject(i);
                if(candidate!=null&&display.owner.equals(candidate.optString("conversationId",candidate.optString("id")))) {task=candidate;break;}
            }
            taskLabel=task==null?display.owner:task.optString("label",display.owner);
            // Presence of a screen does not imply that its worker is still running.
            taskStatus=task==null?"Task status unavailable":task.optBoolean("isStopping")?"Stopping"
                :!task.isNull("currentGeneration")?"Working":"Idle · "+task.optString("status","unknown");
        }
        return "Display "+display.id+" · "+taskLabel+"\n"+taskStatus+(display.viewable()?"":" · "+display.state);
    }

    private void open(AgentDisplayCoordinator.AvailableDisplay display) {
        if(preview!=null||closed)return;
        // Keep the picker open behind the preview so Close returns to the list.
        selected=display;
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12),dp(8),dp(12),dp(8));
        previewStatus=text(label(display)+"\nOpening · View only");panel.addView(previewStatus);
        viewport=new FrameLayout(activity);viewport.setBackgroundColor(Color.BLACK);
        surface=new SurfaceView(activity);
        surface.setContentDescription("Live virtual display · view only");
        // Consume local touches; a click has no action and never reaches the worker.
        surface.setOnTouchListener((view,event)->{
            if(event.getAction()==android.view.MotionEvent.ACTION_UP)view.performClick();
            return true;
        });
        surface.setFocusable(false);
        viewport.addView(surface,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));
        viewport.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->sizeSurface());
        panel.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Display "+display.id).setView(panel).setNegativeButton("Close",null).create();
        preview=dialog;
        SurfaceView target=surface;
        target.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if(preview==null||surface!=target)return;
                Attachment next=new Attachment(display.id);attachment=next;
                io.execute(()->{
                    try {
                        next.client=displays.previewClient(display);
                        next.client.showPreview(display.id,holder.getSurface(),next.token);
                    } catch(Exception failure) {
                        main.post(()->{
                            if(attachment==next&&preview!=null) {
                                endAttachment();target.setVisibility(View.GONE);
                                previewStatus.setText("Preview unavailable. The display may have closed, disconnected, or already be open in another window.");
                            }
                        });
                    }
                });
            }
            @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {if(surface==target)endAttachment();}
        });
        dialog.setOnDismissListener(ignored->previewDismissed(dialog));
        preview.show();
        Window window=preview.getWindow();
        if(window!=null) {
            window.setGravity(Gravity.CENTER);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            android.graphics.Rect bounds=activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            window.setLayout((int)(bounds.width()*.9),(int)(bounds.height()*.75));
        }
        sizeSurface();refresh();
    }

    @SuppressWarnings("deprecation")
    private void sizeSurface() {
        if(selected==null||viewport==null||surface==null)return;
        Display display=activity.getSystemService(DisplayManager.class).getDisplay(selected.id);
        if(display==null)return;
        Point size=new Point();display.getRealSize(size);
        if(size.x<=0||size.y<=0||viewport.getWidth()<=0||viewport.getHeight()<=0)return;
        // Keep the existing display dimensions; scale only its on-screen view.
        surface.getHolder().setFixedSize(size.x,size.y);
        float scale=Math.min((float)viewport.getWidth()/size.x,(float)viewport.getHeight()/size.y);
        int width=Math.max(1,(int)(size.x*scale)),height=Math.max(1,(int)(size.y*scale));
        FrameLayout.LayoutParams layout=(FrameLayout.LayoutParams)surface.getLayoutParams();
        if(layout.width!=width||layout.height!=height) {
            layout.width=width;layout.height=height;surface.setLayoutParams(layout);
        }
    }

    private void endAttachment() {
        Attachment ending=attachment;attachment=null;
        if(ending!=null)io.execute(()->{if(ending.client!=null)ending.client.hidePreview(ending.displayId,ending.token);});
    }
    private void previewDismissed(AlertDialog dialog) {
        if(preview!=dialog)return;
        endAttachment();preview=null;selected=null;surface=null;viewport=null;previewStatus=null;
        stopRefreshIfHidden();
    }
    void dismiss() {
        revision++;main.removeCallbacks(refresh);
        if(preview!=null) {
            AlertDialog closing=preview;
            // Dialog dismissal callbacks may be queued until after onDestroy.
            // Queue detach now, before shutting down this Activity's executor.
            previewDismissed(closing);closing.dismiss();
        }
        if(picker!=null) {AlertDialog closing=picker;picker=null;closing.dismiss();}
    }
    @Override public void close() {
        dismiss();closed=true;
        // Let queued detach finish even when this Activity is destroyed.
        io.shutdown();
    }
    private TextView text(String value) {TextView view=new TextView(activity);view.setText(value);view.setPadding(0,dp(6),0,dp(6));return view;}
    private int dp(int value) {return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
