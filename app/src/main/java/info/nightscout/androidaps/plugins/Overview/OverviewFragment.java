package info.nightscout.androidaps.plugins.Overview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.CardView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.ValueDependentColor;
import com.jjoe64.graphview.series.BarGraphSeries;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.series.PointsGraphSeries;
import com.squareup.otto.Subscribe;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.TempBasal;
import info.nightscout.androidaps.db.Treatment;
import info.nightscout.androidaps.events.EventInitializationChanged;
import info.nightscout.androidaps.events.EventNewBG;
import info.nightscout.androidaps.events.EventNewBasalProfile;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.events.EventRefreshGui;
import info.nightscout.androidaps.events.EventTempBasalChange;
import info.nightscout.androidaps.events.EventTreatmentChange;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.plugins.Careportal.Dialogs.NewNSTreatmentDialog;
import info.nightscout.androidaps.plugins.Careportal.OptionsToShow;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.Loop.LoopPlugin;
import info.nightscout.androidaps.plugins.Loop.events.EventNewOpenLoopNotification;
import info.nightscout.androidaps.plugins.Objectives.ObjectivesPlugin;
import info.nightscout.androidaps.plugins.OpenAPSMA.IobTotal;
import info.nightscout.androidaps.plugins.Overview.Dialogs.NewTreatmentDialog;
import info.nightscout.androidaps.plugins.Overview.Dialogs.WizardDialog;
import info.nightscout.androidaps.plugins.Overview.GraphSeriesExtension.PointsWithLabelGraphSeries;
import info.nightscout.androidaps.plugins.Overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.Overview.events.EventNewNotification;
import info.nightscout.client.data.NSProfile;
import info.nightscout.utils.BolusWizard;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.DecimalFormatter;
import info.nightscout.utils.Round;
import info.nightscout.utils.SafeParse;


public class OverviewFragment extends Fragment {
    private static Logger log = LoggerFactory.getLogger(OverviewFragment.class);

    private static OverviewPlugin overviewPlugin = new OverviewPlugin();
    private SharedPreferences prefs;

    public static OverviewPlugin getPlugin() {
        return overviewPlugin;
    }

    TextView bgView;
    TextView arrowView;
    TextView timeAgoView;
    TextView deltaView;
    TextView avgdeltaView;
    TextView runningTempView;
    TextView baseBasalView;
    TextView activeProfileView;
    TextView iobView;
    TextView apsModeView;
    GraphView bgGraph;

    RecyclerView notificationsView;
    LinearLayoutManager llm;

    LinearLayout cancelTempLayout;
    LinearLayout acceptTempLayout;
    LinearLayout quickWizardLayout;
    Button cancelTempButton;
    Button treatmentButton;
    Button wizardButton;
    Button acceptTempButton;
    Button quickWizardButton;

    Handler sLoopHandler = new Handler();
    Runnable sRefreshLoop = null;

    private static Handler sHandler;
    private static HandlerThread sHandlerThread;

    public OverviewFragment() {
        super();
        if (sHandlerThread == null) {
            sHandlerThread = new HandlerThread(OverviewFragment.class.getSimpleName());
            sHandlerThread.start();
            sHandler = new Handler(sHandlerThread.getLooper());
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        View view = inflater.inflate(R.layout.overview_fragment, container, false);
        bgView = (TextView) view.findViewById(R.id.overview_bg);
        arrowView = (TextView) view.findViewById(R.id.overview_arrow);
        timeAgoView = (TextView) view.findViewById(R.id.overview_timeago);
        deltaView = (TextView) view.findViewById(R.id.overview_delta);
        avgdeltaView = (TextView) view.findViewById(R.id.overview_avgdelta);
        runningTempView = (TextView) view.findViewById(R.id.overview_runningtemp);
        baseBasalView = (TextView) view.findViewById(R.id.overview_basebasal);
        activeProfileView = (TextView) view.findViewById(R.id.overview_activeprofile);

        iobView = (TextView) view.findViewById(R.id.overview_iob);
        apsModeView = (TextView) view.findViewById(R.id.overview_apsmode);
        bgGraph = (GraphView) view.findViewById(R.id.overview_bggraph);
        cancelTempButton = (Button) view.findViewById(R.id.overview_canceltemp);
        treatmentButton = (Button) view.findViewById(R.id.overview_treatment);
        wizardButton = (Button) view.findViewById(R.id.overview_wizard);
        cancelTempButton = (Button) view.findViewById(R.id.overview_canceltemp);
        cancelTempLayout = (LinearLayout) view.findViewById(R.id.overview_canceltemplayout);
        acceptTempButton = (Button) view.findViewById(R.id.overview_accepttempbutton);
        acceptTempLayout = (LinearLayout) view.findViewById(R.id.overview_accepttemplayout);
        quickWizardButton = (Button) view.findViewById(R.id.overview_quickwizard);
        quickWizardLayout = (LinearLayout) view.findViewById(R.id.overview_quickwizardlayout);

        notificationsView = (RecyclerView) view.findViewById(R.id.overview_notifications);
        notificationsView.setHasFixedSize(true);
        llm = new LinearLayoutManager(view.getContext());
        notificationsView.setLayoutManager(llm);

        treatmentButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FragmentManager manager = getFragmentManager();
                NewTreatmentDialog treatmentDialogFragment = new NewTreatmentDialog();
                treatmentDialogFragment.show(manager, "TreatmentDialog");
            }
        });

        wizardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FragmentManager manager = getFragmentManager();
                WizardDialog wizardDialog = new WizardDialog();
                wizardDialog.setContext(getContext());
                wizardDialog.show(manager, "WizardDialog");
            }
        });

        quickWizardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                processQuickWizard();
            }
        });

        cancelTempButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final PumpInterface pump = MainApp.getConfigBuilder();
                if (pump.isTempBasalInProgress()) {
                    sHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            pump.cancelTempBasal();
                            MainApp.bus().post(new EventTempBasalChange());
                        }
                    });
                }
            }
        });


        acceptTempButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainApp.getConfigBuilder().getActiveLoop().invoke(false);
                final LoopPlugin.LastRun finalLastRun = LoopPlugin.lastRun;
                if (finalLastRun != null && finalLastRun.lastAPSRun != null && finalLastRun.constraintsProcessed.changeRequested) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                    builder.setTitle(getContext().getString(R.string.confirmation));
                    builder.setMessage(getContext().getString(R.string.setbasalquestion) + "\n" + finalLastRun.constraintsProcessed);
                    builder.setPositiveButton(getContext().getString(R.string.ok), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            sHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    hideTempRecommendation();
                                    PumpEnactResult applyResult = MainApp.getConfigBuilder().applyAPSRequest(finalLastRun.constraintsProcessed);
                                    if (applyResult.enacted) {
                                        finalLastRun.setByPump = applyResult;
                                        finalLastRun.lastEnact = new Date();
                                        finalLastRun.lastOpenModeAccept = new Date();
                                        MainApp.getConfigBuilder().uploadDeviceStatus();
                                        ObjectivesPlugin objectivesPlugin = (ObjectivesPlugin) MainApp.getSpecificPlugin(ObjectivesPlugin.class);
                                        if (objectivesPlugin != null) {
                                            objectivesPlugin.manualEnacts++;
                                            objectivesPlugin.saveProgress();
                                        }
                                    }
                                    updateGUIIfVisible();
                                }
                            });
                        }
                    });
                    builder.setNegativeButton(getContext().getString(R.string.cancel), null);
                    builder.show();
                }
                updateGUI();
            }
        });

        updateGUI();
        return view;
    }

    void processQuickWizard() {
        final BgReading lastBG = MainApp.getDbHelper().lastBg();
        if (MainApp.getConfigBuilder() == null || ConfigBuilderPlugin.getActiveProfile() == null) // app not initialized yet
            return;
        final NSProfile profile = ConfigBuilderPlugin.getActiveProfile().getProfile();

        QuickWizard.QuickWizardEntry quickWizardEntry = getPlugin().quickWizard.getActive();
        if (quickWizardEntry != null && lastBG != null) {
            quickWizardLayout.setVisibility(View.VISIBLE);
            String text = MainApp.sResources.getString(R.string.bolus) + ": " + quickWizardEntry.buttonText();
            BolusWizard wizard = new BolusWizard();
            wizard.doCalc(profile.getDefaultProfile(), quickWizardEntry.carbs(), lastBG.valueToUnits(profile.getUnits()), 0d, true, true);

            final JSONObject boluscalcJSON = new JSONObject();
            try {
                boluscalcJSON.put("eventTime", DateUtil.toISOString(new Date()));
                boluscalcJSON.put("targetBGLow", wizard.targetBGLow);
                boluscalcJSON.put("targetBGHigh", wizard.targetBGHigh);
                boluscalcJSON.put("isf", wizard.sens);
                boluscalcJSON.put("ic", wizard.ic);
                boluscalcJSON.put("iob", -(wizard.insulingFromBolusIOB + wizard.insulingFromBasalsIOB));
                boluscalcJSON.put("bolusiobused", true);
                boluscalcJSON.put("basaliobused", true);
                boluscalcJSON.put("bg", lastBG.valueToUnits(profile.getUnits()));
                boluscalcJSON.put("insulinbg", wizard.insulinFromBG);
                boluscalcJSON.put("insulinbgused", true);
                boluscalcJSON.put("bgdiff", wizard.bgDiff);
                boluscalcJSON.put("insulincarbs", wizard.insulinFromCarbs);
                boluscalcJSON.put("carbs", quickWizardEntry.carbs());
                boluscalcJSON.put("othercorrection", 0d);
                boluscalcJSON.put("insulin", wizard.calculatedTotalInsulin);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            if (wizard.calculatedTotalInsulin > 0d && quickWizardEntry.carbs() > 0d) {
                DecimalFormat formatNumber2decimalplaces = new DecimalFormat("0.00");
                String confirmMessage = getString(R.string.entertreatmentquestion);

                Double insulinAfterConstraints = MainApp.getConfigBuilder().applyBolusConstraints(wizard.calculatedTotalInsulin);
                Integer carbsAfterConstraints = MainApp.getConfigBuilder().applyCarbsConstraints(quickWizardEntry.carbs());

                confirmMessage += "\n" + getString(R.string.bolus) + ": " + formatNumber2decimalplaces.format(insulinAfterConstraints) + "U";
                confirmMessage += "\n" + getString(R.string.carbs) + ": " + carbsAfterConstraints + "g";

                if (insulinAfterConstraints - wizard.calculatedTotalInsulin != 0 || carbsAfterConstraints != quickWizardEntry.carbs()) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                    builder.setTitle(MainApp.sResources.getString(R.string.treatmentdeliveryerror));
                    builder.setMessage(getString(R.string.constraints_violation) + "\n" + getString(R.string.changeyourinput));
                    builder.setPositiveButton(MainApp.sResources.getString(R.string.ok), null);
                    builder.show();
                    return;
                }

                final Double finalInsulinAfterConstraints = insulinAfterConstraints;
                final Integer finalCarbsAfterConstraints = carbsAfterConstraints;

                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle(MainApp.sResources.getString(R.string.confirmation));
                builder.setMessage(confirmMessage);
                builder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        if (finalInsulinAfterConstraints > 0 || finalCarbsAfterConstraints > 0) {
                            final ConfigBuilderPlugin pump = MainApp.getConfigBuilder();
                            sHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    PumpEnactResult result = pump.deliverTreatmentFromBolusWizard(
                                            getContext(),
                                            finalInsulinAfterConstraints,
                                            finalCarbsAfterConstraints,
                                            lastBG.valueToUnits(profile.getUnits()),
                                            "Manual",
                                            0,
                                            boluscalcJSON
                                    );
                                    if (!result.success) {
                                        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                                        builder.setTitle(MainApp.sResources.getString(R.string.treatmentdeliveryerror));
                                        builder.setMessage(result.comment);
                                        builder.setPositiveButton(MainApp.sResources.getString(R.string.ok), null);
                                        builder.show();
                                    }
                                }
                            });
                        }
                    }
                });
                builder.setNegativeButton(getString(R.string.cancel), null);
                builder.show();
            }
        }

    }

    @Override
    public void onPause() {
        super.onPause();
        MainApp.bus().unregister(this);
        sLoopHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        MainApp.bus().register(this);
        sRefreshLoop = new Runnable() {
            @Override
            public void run() {
                updateGUIIfVisible();
                sLoopHandler.postDelayed(sRefreshLoop, 60 * 1000L);
            }
        };
        sLoopHandler.postDelayed(sRefreshLoop, 60 * 1000L);
        updateGUIIfVisible();
    }

    @Subscribe
    public void onStatusEvent(final EventInitializationChanged ev) {
        updateGUIIfVisible();
    }

    @Subscribe
    public void onStatusEvent(final EventPreferenceChange ev) {
        updateGUIIfVisible();
    }

    @Subscribe
    public void onStatusEvent(final EventRefreshGui ev) {
        updateGUIIfVisible();
    }

    @Subscribe
    public void onStatusEvent(final EventTreatmentChange ev) {
        updateGUIIfVisible();
    }

    @Subscribe
    public void onStatusEvent(final EventTempBasalChange ev) {
        updateGUIIfVisible();
    }

    @Subscribe
    public void onStatusEvent(final EventNewBG ev) {
        updateGUIIfVisible();
    }

    @Subscribe
    public void onStatusEvent(final EventNewOpenLoopNotification ev) {
        updateGUIIfVisible();
    }

    @Subscribe
    public void onStatusEvent(final EventNewBasalProfile ev) { updateGUIIfVisible(); }

    @Subscribe
    public void onStatusEvent(final EventNewNotification n) { updateNotifications(); }

    @Subscribe
    public void onStatusEvent(final EventDismissNotification n) { updateNotifications(); }

    private void hideTempRecommendation() {
        Activity activity = getActivity();
        if (activity != null)
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    acceptTempLayout.setVisibility(View.GONE);
                }
            });
    }

    private void updateGUIIfVisible() {
        Activity activity = getActivity();
        if (activity != null)
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateGUI();
                }
            });
    }

    @SuppressLint("SetTextI18n")
    public void updateGUI() {
        updateNotifications();
        BgReading actualBG = MainApp.getDbHelper().actualBg();
        BgReading lastBG = MainApp.getDbHelper().lastBg();
        if (MainApp.getConfigBuilder() == null || MainApp.getConfigBuilder().getActiveProfile() == null) // app not initialized yet
            return;

        // Skip if not initialized yet
        if (bgGraph == null)
            return;

        if (getActivity() == null)
            return;

        // open loop mode
        final LoopPlugin.LastRun finalLastRun = LoopPlugin.lastRun;
        if (Config.APS && MainApp.getConfigBuilder().getPumpDescription().isTempBasalCapable) {
            apsModeView.setVisibility(View.VISIBLE);
            apsModeView.setBackgroundResource(R.drawable.loopmodeborder);
            apsModeView.setTextColor(Color.BLACK);
            final LoopPlugin activeloop = MainApp.getConfigBuilder().getActiveLoop();
            if(activeloop != null && activeloop.isEnabled(activeloop.getType())) {
                if (MainApp.getConfigBuilder().isClosedModeEnabled()) {
                    apsModeView.setText(MainApp.sResources.getString(R.string.closedloop));
                } else {
                    apsModeView.setText(MainApp.sResources.getString(R.string.openloop));
                }
            } else {
                apsModeView.setBackgroundResource(R.drawable.loopmodedisabledborder);
                apsModeView.setText(MainApp.sResources.getString(R.string.disabledloop));
                apsModeView.setTextColor(Color.WHITE);

            }


            apsModeView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    if (activeloop == null){
                        log.error("no active loop?");
                        return true;
                    } else if (activeloop.isEnabled(PluginBase.LOOP)){
                        activeloop.setFragmentEnabled(PluginBase.LOOP, false);
                        activeloop.setFragmentVisible(PluginBase.LOOP, false);
                    } else {
                        activeloop.setFragmentEnabled(PluginBase.LOOP, true);
                        activeloop.setFragmentVisible(PluginBase.LOOP, true);
                    }
                    MainApp.getConfigBuilder().storeSettings();
                    MainApp.bus().post(new EventRefreshGui(false));
                    return true;
                }
            });
            apsModeView.setLongClickable(true);

        } else {
            apsModeView.setVisibility(View.GONE);
        }

        // **** Temp button ****
        NSProfile profile = MainApp.getConfigBuilder().getActiveProfile().getProfile();
        PumpInterface pump = MainApp.getConfigBuilder();

        boolean showAcceptButton = !MainApp.getConfigBuilder().isClosedModeEnabled(); // Open mode needed
        showAcceptButton = showAcceptButton && finalLastRun != null && finalLastRun.lastAPSRun != null; // aps result must exist
        showAcceptButton = showAcceptButton && (finalLastRun.lastOpenModeAccept == null || finalLastRun.lastOpenModeAccept.getTime() < finalLastRun.lastAPSRun.getTime()); // never accepted or before last result
        showAcceptButton = showAcceptButton && finalLastRun.constraintsProcessed.changeRequested; // change is requested

        if (showAcceptButton && pump.isInitialized()) {
            acceptTempLayout.setVisibility(View.VISIBLE);
            acceptTempButton.setText(getContext().getString(R.string.setbasalquestion) + "\n" + finalLastRun.constraintsProcessed);
        } else {
            acceptTempLayout.setVisibility(View.GONE);
        }

        if (pump.isTempBasalInProgress()) {
            TempBasal activeTemp = pump.getTempBasal();
            cancelTempLayout.setVisibility(View.VISIBLE);
            cancelTempButton.setText(MainApp.instance().getString(R.string.cancel) + ": " + activeTemp.toString());
            runningTempView.setVisibility(View.VISIBLE);
            runningTempView.setText(activeTemp.toString());
        } else {
            cancelTempLayout.setVisibility(View.GONE);
            runningTempView.setVisibility(View.GONE);
        }
        baseBasalView.setText(DecimalFormatter.to2Decimal(pump.getBaseBasalRate()) + " U/h");

        if (profile != null && profile.getActiveProfile() != null)
            activeProfileView.setText(profile.getActiveProfile());

        activeProfileView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                NewNSTreatmentDialog newDialog = new NewNSTreatmentDialog();
                final OptionsToShow profileswitch = new OptionsToShow(R.id.careportal_profileswitch, R.string.careportal_profileswitch, true, false, false, false, false, false, false, true, false);
                profileswitch.executeProfileSwitch = true;
                newDialog.setOptions(profileswitch);
                newDialog.show(getFragmentManager(), "NewNSTreatmentDialog");
                return true;
            }
        });
        activeProfileView.setLongClickable(true);

        if (profile == null || !pump.isInitialized()) {
            // disable all treatment buttons because we are not able to check constraints without profile
            wizardButton.setVisibility(View.INVISIBLE);
            treatmentButton.setVisibility(View.INVISIBLE);
            return;
        } else {
            wizardButton.setVisibility(View.VISIBLE);
            treatmentButton.setVisibility(View.VISIBLE);
        }

        String units = profile.getUnits();

        // QuickWizard button
        QuickWizard.QuickWizardEntry quickWizardEntry = getPlugin().quickWizard.getActive();
        if (quickWizardEntry != null && lastBG != null && pump.isInitialized()) {
            quickWizardLayout.setVisibility(View.VISIBLE);
            String text = MainApp.sResources.getString(R.string.bolus) + ": " + quickWizardEntry.buttonText() + " " + DecimalFormatter.to0Decimal(quickWizardEntry.carbs()) + "g";
            BolusWizard wizard = new BolusWizard();
            wizard.doCalc(profile.getDefaultProfile(), quickWizardEntry.carbs(), lastBG.valueToUnits(profile.getUnits()), 0d, true, true);
            text += " " + DecimalFormatter.to2Decimal(wizard.calculatedTotalInsulin) + "U";
            quickWizardButton.setText(text);
            if (wizard.calculatedTotalInsulin <= 0)
                quickWizardLayout.setVisibility(View.GONE);
        } else
            quickWizardLayout.setVisibility(View.GONE);

        // **** BG value ****
        if (lastBG != null && bgView != null) {
            bgView.setText(lastBG.valueToUnitsToString(profile.getUnits()));
            arrowView.setText(lastBG.directionToSymbol());
            DatabaseHelper.GlucoseStatus glucoseStatus = MainApp.getDbHelper().getGlucoseStatusData();
            if (glucoseStatus != null){
                deltaView.setText("Δ " + NSProfile.toUnitsString(glucoseStatus.delta, glucoseStatus.delta * Constants.MGDL_TO_MMOLL, units) + " " + units);
                avgdeltaView.setText("øΔ " + NSProfile.toUnitsString(glucoseStatus.avgdelta, glucoseStatus.avgdelta * Constants.MGDL_TO_MMOLL, units) + " " + units);
            }

            BgReading.units = profile.getUnits();
        } else
            return;

        Integer flag = bgView.getPaintFlags();
        if (actualBG == null) {
            flag |= Paint.STRIKE_THRU_TEXT_FLAG;
        } else
            flag &= ~Paint.STRIKE_THRU_TEXT_FLAG;
        bgView.setPaintFlags(flag);

        Long agoMsec = new Date().getTime() - lastBG.timeIndex;
        int agoMin = (int) (agoMsec / 60d / 1000d);
        timeAgoView.setText(String.format(MainApp.sResources.getString(R.string.minago), agoMin));

        // iob
        MainApp.getConfigBuilder().getActiveTreatments().updateTotalIOB();
        IobTotal bolusIob = MainApp.getConfigBuilder().getActiveTreatments().getLastCalculation().round();
        if (bolusIob == null) bolusIob = new IobTotal();
        MainApp.getConfigBuilder().getActiveTempBasals().updateTotalIOB();
        IobTotal basalIob = MainApp.getConfigBuilder().getActiveTempBasals().getLastCalculation().round();
        if (basalIob == null) basalIob = new IobTotal();

        String iobtext = getString(R.string.treatments_iob_label_string) + " " + DecimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob) + "U ("
                + getString(R.string.bolus) + ": " + DecimalFormatter.to2Decimal(bolusIob.iob) + "U "
                + getString(R.string.basal) + ": " + DecimalFormatter.to2Decimal(basalIob.basaliob) + "U)";
        iobView.setText(iobtext);

        // ****** GRAPH *******

        // allign to hours
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(new Date().getTime());
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.add(Calendar.HOUR, 1);

        int hoursToFetch = 6;
        long toTime = calendar.getTimeInMillis() + 100000; // little bit more to avoid wrong rounding
        long fromTime = toTime - hoursToFetch * 60 * 60 * 1000L;

        Double lowLine = SafeParse.stringToDouble(prefs.getString("low_mark", "0"));
        Double highLine = SafeParse.stringToDouble(prefs.getString("high_mark", "0"));

        if (lowLine < 1){
            lowLine = NSProfile.fromMgdlToUnits(OverviewPlugin.bgTargetLow, units);
        }

        if(highLine < 1){
            highLine = NSProfile.fromMgdlToUnits(OverviewPlugin.bgTargetHigh, units);
        }

        BarGraphSeries<DataPoint> basalsSeries = null;
        LineGraphSeries<DataPoint> seriesLow = null;
        LineGraphSeries<DataPoint> seriesHigh = null;
        LineGraphSeries<DataPoint> seriesNow = null;
        PointsGraphSeries<BgReading> seriesInRage = null;
        PointsGraphSeries<BgReading> seriesOutOfRange = null;
        PointsWithLabelGraphSeries<Treatment> seriesTreatments = null;

        // remove old data from graph
        bgGraph.removeAllSeries();

        // **** HIGH and LOW targets graph ****
        DataPoint[] lowDataPoints = new DataPoint[]{
                new DataPoint(fromTime, lowLine),
                new DataPoint(toTime, lowLine)
        };
        DataPoint[] highDataPoints = new DataPoint[]{
                new DataPoint(fromTime, highLine),
                new DataPoint(toTime, highLine)
        };
        bgGraph.addSeries(seriesLow = new LineGraphSeries<DataPoint>(lowDataPoints));
        seriesLow.setColor(Color.RED);
        bgGraph.addSeries(seriesHigh = new LineGraphSeries<DataPoint>(highDataPoints));
        seriesHigh.setColor(Color.RED);

        // **** TEMP BASALS graph ****
        class BarDataPoint extends DataPoint {
            public BarDataPoint(double x, double y, boolean isTempBasal) {
                super(x, y);
                this.isTempBasal = isTempBasal;
            }

            public boolean isTempBasal = false;
        }

        Double maxAllowedBasal = MainApp.getConfigBuilder().applyBasalConstraints(Constants.basalAbsoluteOnlyForCheckLimit);
        Double maxBasalValueFound = 0d;

        long now = new Date().getTime();
        List<BarDataPoint> basalArray = new ArrayList<BarDataPoint>();
        for (long time = fromTime; time < now; time += 5 * 60 * 1000L) {
            TempBasal tb = MainApp.getConfigBuilder().getTempBasal(new Date(time));
            Double basal = 0d;
            if (tb != null)
                basalArray.add(new BarDataPoint(time, basal = tb.tempBasalConvertedToAbsolute(new Date(time)), true));
            else
                basalArray.add(new BarDataPoint(time, basal = profile.getBasal(NSProfile.secondsFromMidnight(new Date(time))), false));
            maxBasalValueFound = Math.max(maxBasalValueFound, basal);
        }
        BarDataPoint[] basal = new BarDataPoint[basalArray.size()];
        basal = basalArray.toArray(basal);
        bgGraph.addSeries(basalsSeries = new BarGraphSeries<DataPoint>(basal));
        basalsSeries.setValueDependentColor(new ValueDependentColor<DataPoint>() {
            @Override
            public int get(DataPoint data) {
                BarDataPoint point = (BarDataPoint) data;
                if (point.isTempBasal) return Color.BLUE;
                else return Color.CYAN;
            }
        });

        // set manual x bounds to have nice steps
        bgGraph.getViewport().setMaxX(toTime);
        bgGraph.getViewport().setMinX(fromTime);
        bgGraph.getViewport().setXAxisBoundsManual(true);
        bgGraph.getGridLabelRenderer().setLabelFormatter(new TimeAsXAxisLabelFormatter(getActivity(), "HH"));
        bgGraph.getGridLabelRenderer().setNumHorizontalLabels(7); // only 7 because of the space

        // **** BG graph ****
        List<BgReading> bgReadingsArray = MainApp.getDbHelper().getDataFromTime(fromTime);
        List<BgReading> inRangeArray = new ArrayList<BgReading>();
        List<BgReading> outOfRangeArray = new ArrayList<BgReading>();

        if (bgReadingsArray.size() == 0)
            return;

        Iterator<BgReading> it = bgReadingsArray.iterator();
        Double maxBgValue = 0d;
        while (it.hasNext()) {
            BgReading bg = it.next();
            if (bg.value > maxBgValue) maxBgValue = bg.value;
            if (bg.valueToUnits(units) < lowLine || bg.valueToUnits(units) > highLine)
                outOfRangeArray.add(bg);
            else
                inRangeArray.add(bg);
        }
        maxBgValue = NSProfile.fromMgdlToUnits(maxBgValue, units);
        maxBgValue = units.equals(Constants.MGDL) ? Round.roundTo(maxBgValue, 40d) + 80 : Round.roundTo(maxBgValue, 2d) + 4;
        if(highLine > maxBgValue) maxBgValue = highLine;
        Integer numOfHorizLines = units.equals(Constants.MGDL) ? (int) (maxBgValue / 40 + 1) : (int) (maxBgValue / 2 + 1);

        BgReading[] inRange = new BgReading[inRangeArray.size()];
        BgReading[] outOfRange = new BgReading[outOfRangeArray.size()];
        inRange = inRangeArray.toArray(inRange);
        outOfRange = outOfRangeArray.toArray(outOfRange);


        if (inRange.length > 0) {
            bgGraph.addSeries(seriesInRage = new PointsGraphSeries<BgReading>(inRange));
            seriesInRage.setShape(PointsGraphSeries.Shape.POINT);
            seriesInRage.setSize(5);
            seriesInRage.setColor(Color.GREEN);
        }

        if (outOfRange.length > 0) {
            bgGraph.addSeries(seriesOutOfRange = new PointsGraphSeries<BgReading>(outOfRange));
            seriesOutOfRange.setShape(PointsGraphSeries.Shape.POINT);
            seriesOutOfRange.setSize(5);
            seriesOutOfRange.setColor(Color.RED);
        }

        // **** NOW line ****
        DataPoint[] nowPoints = new DataPoint[]{
                new DataPoint(now, 0),
                new DataPoint(now, maxBgValue)
        };
        bgGraph.addSeries(seriesNow = new LineGraphSeries<DataPoint>(nowPoints));
        seriesNow.setColor(Color.GREEN);
        seriesNow.setDrawDataPoints(false);
        //seriesNow.setThickness(1);
        // custom paint to make a dotted line
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setPathEffect(new DashPathEffect(new float[]{4, 20}, 0));
        paint.setColor(Color.WHITE);
        seriesNow.setCustomPaint(paint);


        // Treatments
        List<Treatment> treatments = MainApp.getConfigBuilder().getActiveTreatments().getTreatments();
        List<Treatment> filteredTreatments = new ArrayList<Treatment>();

        for (int tx = 0; tx < treatments.size(); tx++) {
            Treatment t = treatments.get(tx);
            if (t.getTimeIndex() < fromTime || t.getTimeIndex() > now) continue;
            t.setYValue(bgReadingsArray);
            filteredTreatments.add(t);
        }
        Treatment[] treatmentsArray = new Treatment[filteredTreatments.size()];
        treatmentsArray = filteredTreatments.toArray(treatmentsArray);
        if (treatmentsArray.length > 0) {
            bgGraph.addSeries(seriesTreatments = new PointsWithLabelGraphSeries<Treatment>(treatmentsArray));
            seriesTreatments.setShape(PointsWithLabelGraphSeries.Shape.TRIANGLE);
            seriesTreatments.setSize(10);
            seriesTreatments.setColor(Color.CYAN);
        }

        // set manual y bounds to have nice steps
        bgGraph.getViewport().setMaxY(maxBgValue);
        bgGraph.getViewport().setMinY(0);
        bgGraph.getViewport().setYAxisBoundsManual(true);
        bgGraph.getGridLabelRenderer().setNumVerticalLabels(numOfHorizLines);

        // set second scale
        bgGraph.getSecondScale().addSeries(basalsSeries);
        bgGraph.getSecondScale().setMinY(0);
        bgGraph.getSecondScale().setMaxY(maxBgValue / lowLine * maxBasalValueFound * 1.2d);
        bgGraph.getGridLabelRenderer().setVerticalLabelsSecondScaleColor(MainApp.instance().getResources().getColor(R.color.background_material_dark)); // same color as backround = hide


    }

    //Notifications
    public static class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.NotificationsViewHolder> {

        List<Notification> notificationsList;

        RecyclerViewAdapter(List<Notification> notificationsList) {
            this.notificationsList = notificationsList;
        }

        @Override
        public NotificationsViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            View v = LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.overview_notification_item, viewGroup, false);
            NotificationsViewHolder notificationsViewHolder = new NotificationsViewHolder(v);
            return notificationsViewHolder;
        }

        @Override
        public void onBindViewHolder(NotificationsViewHolder holder, int position) {
            DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
            Notification notification = notificationsList.get(position);
            holder.dismiss.setTag(notification);
            holder.text.setText(notification.text);
            holder.time.setText(df.format(notification.date));
            if (notification.level == Notification.URGENT)
                holder.cv.setBackgroundColor(MainApp.instance().getResources().getColor(R.color.notificationUrgent));
            else if (notification.level == Notification.NORMAL)
                holder.cv.setBackgroundColor(MainApp.instance().getResources().getColor(R.color.notificationNormal));
            else if (notification.level == Notification.LOW)
                holder.cv.setBackgroundColor(MainApp.instance().getResources().getColor(R.color.notificationLow));
            else if (notification.level == Notification.INFO)
                holder.cv.setBackgroundColor(MainApp.instance().getResources().getColor(R.color.notificationInfo));
        }

        @Override
        public int getItemCount() {
            return notificationsList.size();
        }

        @Override
        public void onAttachedToRecyclerView(RecyclerView recyclerView) {
            super.onAttachedToRecyclerView(recyclerView);
        }

        public static class NotificationsViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
            CardView cv;
            TextView time;
            TextView text;
            Button dismiss;

            NotificationsViewHolder(View itemView) {
                super(itemView);
                cv = (CardView) itemView.findViewById(R.id.notification_cardview);
                time = (TextView) itemView.findViewById(R.id.notification_time);
                text = (TextView) itemView.findViewById(R.id.notification_text);
                dismiss = (Button) itemView.findViewById(R.id.notification_dismiss);
                dismiss.setOnClickListener(this);
            }

            @Override
            public void onClick(View v) {
                Notification notification = (Notification) v.getTag();
                switch (v.getId()) {
                    case R.id.notification_dismiss:
                        MainApp.bus().post(new EventDismissNotification(notification.id));
                        break;
                }
            }
        }
    }

    void updateNotifications() {
        Activity activity = getActivity();
        if (activity != null)
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    NotificationStore nstore = getPlugin().notificationStore;
                    nstore.removeExpired();
                    if (nstore.store.size() > 0) {
                        RecyclerViewAdapter adapter = new RecyclerViewAdapter(nstore.store);
                        notificationsView.setAdapter(adapter);
                        notificationsView.setVisibility(View.VISIBLE);
                    } else {
                        notificationsView.setVisibility(View.GONE);
                    }
                }
            });
    }



}
