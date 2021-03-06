package com.nutomic.syncthingandroid.fragments;

import android.app.Fragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.SwitchCompat;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.activities.SettingsActivity;
import com.nutomic.syncthingandroid.activities.SyncthingActivity;
import com.nutomic.syncthingandroid.syncthing.RestApi;
import com.nutomic.syncthingandroid.syncthing.SyncthingService;
import com.nutomic.syncthingandroid.util.BarcodeIntentIntegrator;
import com.nutomic.syncthingandroid.util.BarcodeIntentResult;
import com.nutomic.syncthingandroid.util.Compression;
import com.nutomic.syncthingandroid.util.TextWatcherAdapter;

import java.util.List;
import java.util.Map;

import static android.text.TextUtils.isEmpty;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
import static com.nutomic.syncthingandroid.syncthing.SyncthingService.State.ACTIVE;
import static com.nutomic.syncthingandroid.util.Compression.METADATA;

/**
 * Shows device details and allows changing them.
 */
public class DeviceFragment extends Fragment implements
        SyncthingActivity.OnServiceConnectedListener, RestApi.OnReceiveConnectionsListener,
        SyncthingService.OnApiChangeListener, RestApi.OnDeviceIdNormalizedListener,
        View.OnClickListener {

    public static final String EXTRA_NODE_ID = "device_id";

    private static final String TAG = "DeviceSettingsFragment";

    private static final String DYNAMIC_ADDRESSES = "dynamic";

    private SyncthingService mSyncthingService;

    private RestApi.Device mDevice;

    private View mIdContainer;

    private EditText mIdView;

    private View mQrButton;

    private EditText mNameView;

    private EditText mAddressesView;

    private TextView mCurrentAddressView;

    private TextView mCompressionValueView;

    private SwitchCompat mIntroducerView;

    private TextView mSyncthingVersionView;

    private View mCompressionContainer;

    private boolean mIsCreateMode;

    private boolean mDeviceNeedsToUpdate;

    private DialogInterface.OnClickListener mCompressionEntrySelectedListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();

            Compression compression = Compression.fromIndex(which);
            // Don't pop the restart dialog unless the value is actually different.
            if (compression != Compression.fromValue(getActivity(), mDevice.compression)) {
                mDeviceNeedsToUpdate = true;

                mDevice.compression = compression.getValue(getActivity());
                mCompressionValueView.setText(compression.getTitle(getActivity()));
            }
        }
    };

    private TextWatcher mIdTextWatcher = new TextWatcherAdapter() {
        @Override
        public void afterTextChanged(Editable s) {
            if (!s.toString().equals(mDevice.deviceID)) {
                mDeviceNeedsToUpdate = true;

                mDevice.deviceID = s.toString();
            }
        }
    };

    private TextWatcher mNameTextWatcher = new TextWatcherAdapter() {
        @Override
        public void afterTextChanged(Editable s) {
            if (!s.toString().equals(mDevice.name)) {
                mDeviceNeedsToUpdate = true;

                mDevice.name = s.toString();
            }
        }
    };

    private TextWatcher mAddressesTextWatcher = new TextWatcherAdapter() {
        @Override
        public void afterTextChanged(Editable s) {
            if (!s.toString().equals(mDevice.addresses)) {
                mDeviceNeedsToUpdate = true;

                mDevice.addresses = persistableAddresses(s);
            }
        }
    };

    private CompoundButton.OnCheckedChangeListener mIntroducerCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (mDevice.introducer != isChecked) {
                mDeviceNeedsToUpdate = true;

                mDevice.introducer = isChecked;
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SettingsActivity activity = (SettingsActivity) getActivity();
        mIsCreateMode = activity.getIsCreate();
        activity.registerOnServiceConnectedListener(this);
        activity.setTitle(mIsCreateMode ? R.string.add_device : R.string.edit_device);
        setHasOptionsMenu(true);

        if (mIsCreateMode) {
            if (savedInstanceState != null) {
                mDevice = (RestApi.Device) savedInstanceState.getSerializable("device");
            }
            if (mDevice == null) {
                initDevice();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mSyncthingService != null) {
            mSyncthingService.unregisterOnApiChangeListener(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // We don't want to update every time a TextView's character changes,
        // so we hold off until the view stops being visible to the user.
        if (mDeviceNeedsToUpdate) {
            updateDevice();
        }
    }

    /**
     * Save current settings in case we are in create mode and they aren't yet stored in the config.
     */
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable("device", mDevice);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mIdContainer = view.findViewById(R.id.idContainer);
        mIdView = (EditText) view.findViewById(R.id.id);
        mQrButton = view.findViewById(R.id.qrButton);
        mNameView = (EditText) view.findViewById(R.id.name);
        mAddressesView = (EditText) view.findViewById(R.id.addresses);
        mCurrentAddressView = (TextView) view.findViewById(R.id.currentAddress);
        mCompressionContainer = view.findViewById(R.id.compressionContainer);
        mCompressionValueView = (TextView) view.findViewById(R.id.compressionValue);
        mIntroducerView = (SwitchCompat) view.findViewById(R.id.introducer);
        mSyncthingVersionView = (TextView) view.findViewById(R.id.syncthingVersion);

        mQrButton.setOnClickListener(this);
        mCompressionContainer.setOnClickListener(this);

        if (!mIsCreateMode) {
            prepareEditMode();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        mIdView.removeTextChangedListener(mIdTextWatcher);
        mNameView.removeTextChangedListener(mNameTextWatcher);
        mAddressesView.removeTextChangedListener(mAddressesTextWatcher);
    }

    @Override
    public void onServiceConnected() {
        mSyncthingService = ((SyncthingActivity) getActivity()).getService();
        mSyncthingService.registerOnApiChangeListener(this);
    }

    /**
     * Sets version and current address of the device.
     * <p/>
     * NOTE: This is only called once on startup, should be called more often to properly display
     * version/address changes.
     */
    @Override
    public void onReceiveConnections(Map<String, RestApi.Connection> connections) {
        boolean viewsExist = mSyncthingVersionView != null && mCurrentAddressView != null;
        if (viewsExist && connections.containsKey(mDevice.deviceID)) {
            mCurrentAddressView.setVisibility(VISIBLE);
            mSyncthingVersionView.setVisibility(VISIBLE);
            mCurrentAddressView.setText(connections.get(mDevice.deviceID).address);
            mSyncthingVersionView.setText(connections.get(mDevice.deviceID).clientVersion);
        }
    }

    @Override
    public void onApiChange(SyncthingService.State currentState) {
        if (currentState != ACTIVE) {
            getActivity().finish();
            return;
        }

        if (!mIsCreateMode) {
            List<RestApi.Device> devices = mSyncthingService.getApi().getDevices(false);
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).deviceID.equals(
                        getActivity().getIntent().getStringExtra(EXTRA_NODE_ID))) {
                    mDevice = devices.get(i);
                    break;
                }
            }
            if (mDevice == null) {
                Log.w(TAG, "Device not found in API update");
                getActivity().finish();
                return;
            }
        }

        mSyncthingService.getApi().getConnections(this);

        updateViewsAndSetListeners();
    }

    private void updateViewsAndSetListeners() {
        // Update views
        mIdView.setText(mDevice.deviceID);
        mNameView.setText((mDevice.name));
        mAddressesView.setText(displayableAddresses());
        mCompressionValueView.setText(Compression.fromValue(getActivity(), mDevice.compression).getTitle(getActivity()));
        mIntroducerView.setChecked(mDevice.introducer);

        // Keep state updated
        mIdView.addTextChangedListener(mIdTextWatcher);
        mNameView.addTextChangedListener(mNameTextWatcher);
        mAddressesView.addTextChangedListener(mAddressesTextWatcher);
        mIntroducerView.setOnCheckedChangeListener(mIntroducerCheckedChangeListener);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.device_settings, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.create).setVisible(mIsCreateMode);
        menu.findItem(R.id.share_device_id).setVisible(!mIsCreateMode);
        menu.findItem(R.id.delete).setVisible(!mIsCreateMode);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.create:
                if (isEmpty(mDevice.deviceID)) {
                    Toast.makeText(getActivity(), R.string.device_id_required, Toast.LENGTH_LONG)
                            .show();
                    return true;
                }
                if (isEmpty(mDevice.name)) {
                    Toast.makeText(getActivity(), R.string.device_name_required, Toast.LENGTH_LONG)
                            .show();
                    return true;
                }
                mSyncthingService.getApi().editDevice(mDevice, getActivity(), this);
                getActivity().finish();
                return true;
            case R.id.share_device_id:
                RestApi.shareDeviceId(getActivity(), mDevice.deviceID);
                return true;
            case R.id.delete:
                new AlertDialog.Builder(getActivity())
                        .setMessage(R.string.delete_device_confirm)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                mSyncthingService.getApi().deleteDevice(mDevice, getActivity());
                            }
                        })
                        .setNegativeButton(android.R.string.no, null)
                        .show();
                return true;
            case android.R.id.home:
                getActivity().finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Receives value of scanned QR code and sets it as device ID.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        BarcodeIntentResult scanResult = BarcodeIntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
        if (scanResult != null) {
            mDevice.deviceID = scanResult.getContents();
            mIdView.setText(mDevice.deviceID);
        }
    }

    /**
     * Callback for {@link RestApi#editDevice}.
     * Displays an error toast if error message present.
     */
    @Override
    public void onDeviceIdNormalized(String normalizedId, String error) {
        if (error != null) {
            Toast.makeText(getActivity(), error, Toast.LENGTH_LONG).show();
        }
    }

    private void initDevice() {
        mDevice = new RestApi.Device();
        mDevice.name = "";
        mDevice.deviceID = "";
        mDevice.addresses = "dynamic";
        mDevice.compression = METADATA.getValue(getActivity());
        mDevice.introducer = false;
    }

    private void prepareEditMode() {
        getActivity().getWindow().setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        mIdView.setEnabled(false);
        mQrButton.setVisibility(GONE);

        mIdContainer.setOnClickListener(this);
    }

    /**
     * Sends the updated device info if in edit mode.
     */
    private void updateDevice() {
        if (!mIsCreateMode && mDeviceNeedsToUpdate) {
            mSyncthingService.getApi().editDevice(mDevice, getActivity(), this);
        }
    }

    private String persistableAddresses(CharSequence userInput) {
        return isEmpty(userInput) ? DYNAMIC_ADDRESSES : userInput.toString();
    }

    private String displayableAddresses() {
        return DYNAMIC_ADDRESSES.equals(mDevice.addresses) ? "" : mDevice.addresses;
    }

    @Override
    public void onClick(View v) {
        if (v.equals(mCompressionContainer)) {
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.compression)
                    .setSingleChoiceItems(R.array.compress_entries,
                            Compression.fromValue(getActivity(), mDevice.compression).getIndex(),
                            mCompressionEntrySelectedListener)
                    .show();
        } else if (v.equals(mQrButton)){
            BarcodeIntentIntegrator integrator = new BarcodeIntentIntegrator(DeviceFragment.this);
            integrator.initiateScan();
        } else if (v.equals(mIdContainer)) {
            mSyncthingService.getApi().copyDeviceId(mDevice.deviceID);
        }
    }
}
