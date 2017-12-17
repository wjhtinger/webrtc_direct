package com.windsing.webrtc.webrtc_direct;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.RelativeLayout;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRendererGui;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "LoginActivity";

    private static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private static final String AUDIO_TRACK_ID = "ARDAMSa0";
    private static final String MIN_VIDEO_WIDTH_CONSTRAINT = "minWidth";
    private static final int MIN_VIDEO_WIDTH = 640;
    private static final int CAMERA_TYPE_FRONT = 1;
    private static final int CAMERA_TYPE_REAR = 2;
    private static final int CAMERA_TYPE_UNDEFINED = -1;

    private Context mContext;
    private boolean mIsInitialized = false;
    private Boolean mIsSupported;
    private GLSurfaceView mCallView = null;
    private PeerConnectionFactory mPeerConnectionFactory = null;
    private String mFrontCameraName = null;
    private String mBackCameraName = null;
    private VideoCapturer mVideoCapturer = null;
    private int mCameraInUse = CAMERA_TYPE_UNDEFINED;
    private boolean mIsCameraSwitched;
    private boolean mIsVideoSourceStopped = false;
    private VideoSource mVideoSource = null;
    private VideoTrack mLocalVideoTrack = null;
    private AudioSource mAudioSource = null;
    private AudioTrack mLocalAudioTrack = null;
    private MediaStream mLocalMediaStream = null;

    private VideoTrack mRemoteVideoTrack = null;
    private PeerConnection mPeerConnection = null;

    private VideoRenderer mLargeRemoteRenderer = null;
    private VideoRenderer mSmallLocalRenderer = null;
    private VideoRenderer.Callbacks mLargeLocalRendererCallbacks = null;
    private VideoRenderer.Callbacks mSmallLocalRendererCallbacks;
    private VideoRenderer mLargeLocalRenderer = null;

    private boolean mIsIncoming = true;
    private int mCallState = 0;
    private String serverIP = "192.168.1.4";//"10.2.131.148";
    private String clientIP = "";
    private int mServerPort = 2222;
    private int mClientPort = 2222;

    final Handler mUIThreadHandler = new Handler();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = getApplicationContext();

        findViewById(R.id.connect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callStart();
            }
        });

        findViewById(R.id.switch_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendSwitchCamera();
            }
        });

        initCall();
    }

    private void initCall(){
        isSupported(mContext);

        callServerStart();
        createCallView();
        initCallUI(null);

    }

    private boolean isSupported(Context context) {
        if (null == mIsSupported) {
            mIsSupported = Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH;

            // the call initialisation is not yet done
            if (mIsSupported) {
                initializeAndroidGlobals(context.getApplicationContext());
            }

            Log.d(LOG_TAG, "isSupported " + mIsSupported);
        }

        return mIsSupported;
    }

    private void initializeAndroidGlobals(Context context) {
        if (!mIsInitialized) {
            try {
                mIsInitialized = PeerConnectionFactory.initializeAndroidGlobals(
                        context,
                        true, // enable audio initializing
                        true, // enable video initializing
                        true, // enable hardware acceleration
                        VideoRendererGui.getEGLContext());

                PeerConnectionFactory.initializeFieldTrials(null);
                mIsSupported = true;
                Log.d(LOG_TAG, "## initializeAndroidGlobals(): mIsInitialized=" + mIsInitialized);
            } catch (UnsatisfiedLinkError e) {
                Log.e(LOG_TAG, "## initializeAndroidGlobals(): Exception Msg=" + e.getMessage());
                mIsInitialized = true;
                mIsSupported = false;
            } catch (Exception e) {
                Log.e(LOG_TAG, "## initializeAndroidGlobals(): Exception Msg=" + e.getMessage());
                mIsInitialized = true;
                mIsSupported = false;
            }
        }
    }

    private void createCallView() {
        if ((null != mIsSupported) && mIsSupported) {
            Log.d(LOG_TAG, "MXJingleCall createCallView");

            mCallView = new GLSurfaceView(mContext); // set the GLSurfaceView where it should render to
            //mCallView.setVisibility(View.GONE);

            RelativeLayout layout = (RelativeLayout)findViewById(R.id.call_layout);
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
            params.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
            layout.addView(mCallView, 1, params);
        }
    }

    private void initCallUI(IMXCall.VideoLayoutConfiguration aLocalVideoPosition) {
        Log.e(LOG_TAG, "## initCallUI(): IN");

        try {
            // pass a runnable to be run once the surface view is ready
            VideoRendererGui.setView(mCallView, new Runnable() {
                @Override
                public void run() {
                    mUIThreadHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (null == mPeerConnectionFactory) {
                                Log.e(LOG_TAG, "## initCallUI(): video call and no mPeerConnectionFactory");
                                mPeerConnectionFactory = new PeerConnectionFactory();
                                createVideoTrack();
                                createAudioTrack();
                                createLocalStream();
                                createConnect();
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.e(LOG_TAG, "## initCallUI(): VideoRendererGui.setView : Exception Msg =" + e.getMessage());
        }

        // create the renderers after the VideoRendererGui.setView
        try {
            Log.e(LOG_TAG, "## initCallUI() building UI");
            //  create the video displaying the remote view sent by the server
            mLargeRemoteRenderer = VideoRendererGui.createGui(0, 0, 100, 100, VideoRendererGui.ScalingType.SCALE_ASPECT_FILL, false);
            mLargeLocalRendererCallbacks = VideoRendererGui.create(0, 0, 100, 100, VideoRendererGui.ScalingType.SCALE_ASPECT_FILL, true);
            mLargeLocalRenderer = new VideoRenderer(mLargeLocalRendererCallbacks);

            // create the video displaying the local user: horizontal center, just above the video buttons menu
            if (null != aLocalVideoPosition) {
                mSmallLocalRendererCallbacks = VideoRendererGui.create(aLocalVideoPosition.mX, aLocalVideoPosition.mY, aLocalVideoPosition.mWidth, aLocalVideoPosition.mHeight, VideoRendererGui.ScalingType.SCALE_ASPECT_BALANCED, true);
            } else {
                // default layout
                mSmallLocalRendererCallbacks = VideoRendererGui.create(5, 5, 25, 25, VideoRendererGui.ScalingType.SCALE_ASPECT_BALANCED, true);
            }
            mSmallLocalRenderer = new VideoRenderer(mSmallLocalRendererCallbacks);

        } catch (Exception e) {
            Log.e(LOG_TAG, "## initCallUI(): Exception Msg =" + e.getMessage());
        }

        mCallView.setVisibility(View.VISIBLE);

    }

    private VideoTrack createVideoTrack() { // permission crash
        Log.e(LOG_TAG, "createVideoTrack");

        // create the local renderer only if there is a camera on the device
        if (hasCameraDevice()) {
            try {
                if (null != mFrontCameraName) {
                    mVideoCapturer = VideoCapturerAndroid.create(mFrontCameraName);

                    if (null == mVideoCapturer) {
                        Log.e(LOG_TAG, "Cannot create Video Capturer from front camera");
                    } else {
                        mCameraInUse = CAMERA_TYPE_FRONT;
                    }
                }

                if ((null == mVideoCapturer) && (null != mBackCameraName)) {
                    mVideoCapturer = VideoCapturerAndroid.create(mBackCameraName);

                    if (null == mVideoCapturer) {
                        Log.e(LOG_TAG, "Cannot create Video Capturer from back camera");
                    } else {
                        mCameraInUse = CAMERA_TYPE_REAR;
                    }
                }
            } catch (Exception ex2) {
                // catch exception due to Android M permissions, when
                // a call is received and the permissions (camera and audio) were not yet granted
                Log.e(LOG_TAG, "createVideoTrack(): Exception Msg=" + ex2.getMessage());
            }

            if (null != mVideoCapturer) {
                Log.e(LOG_TAG, "createVideoTrack find a video capturer");

                try {
                    MediaConstraints videoConstraints = new MediaConstraints();

                    videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                            MIN_VIDEO_WIDTH_CONSTRAINT, Integer.toString(MIN_VIDEO_WIDTH)));

                    mVideoSource = mPeerConnectionFactory.createVideoSource(mVideoCapturer, videoConstraints);
                    mLocalVideoTrack = mPeerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, mVideoSource);
                    mLocalVideoTrack.setEnabled(true);
                    mLocalVideoTrack.addRenderer(mSmallLocalRenderer);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "createVideoSource fails with exception " + e.getLocalizedMessage());
                    mLocalVideoTrack = null;
                    if (null != mVideoSource) {
                        mVideoSource.dispose();
                        mVideoSource = null;
                    }
                }
            } else {
                Log.e(LOG_TAG, "## createVideoTrack(): Cannot create Video Capturer - no camera available");
            }
        }

        return mLocalVideoTrack;
    }

    private boolean hasCameraDevice() {
        int devicesNumber = 0;
        try {
            devicesNumber = VideoCapturerAndroid.getDeviceCount();
            mFrontCameraName = VideoCapturerAndroid.getNameOfFrontFacingDevice();
            mBackCameraName = VideoCapturerAndroid.getNameOfBackFacingDevice();
        } catch (Exception e) {
            Log.e(LOG_TAG, "hasCameraDevice " + e.getLocalizedMessage());
        }

        Log.e(LOG_TAG, "hasCameraDevice():  camera number= " + devicesNumber);
        Log.e(LOG_TAG, "hasCameraDevice():  frontCameraName=" + mFrontCameraName + " backCameraName=" + mBackCameraName);

        return (null != mFrontCameraName) || (null != mBackCameraName);
    }

    private AudioTrack createAudioTrack() {
        return null;
    }


    private void createLocalStream() {
        Log.e(LOG_TAG, "## createLocalStream(): IN");

        if ((null == mLocalVideoTrack) && (null == mLocalAudioTrack)) {
            Log.e(LOG_TAG, "## createLocalStream(): CALL_ERROR_CALL_INIT_FAILED");
            hangup("no_stream");
            return;
        }

        mLocalMediaStream = mPeerConnectionFactory.createLocalMediaStream("ARDAMS");
        if (null != mLocalVideoTrack) {
            mLocalMediaStream.addTrack(mLocalVideoTrack);
        }
        if (null != mLocalAudioTrack) {
            mLocalMediaStream.addTrack(mLocalAudioTrack);
        }
    }

    private void createConnect(){
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        MediaConstraints pcConstraints = new MediaConstraints();
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("RtpDataChannels", "true"));
        mPeerConnection = mPeerConnectionFactory.createPeerConnection(
                iceServers,
                pcConstraints,
                new PeerConnection.Observer() {
                    @Override
                    public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onSignalingChange state=" + signalingState);
                    }

                    @Override
                    public void onIceConnectionChange(final PeerConnection.IceConnectionState iceConnectionState) {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onIceConnectionChange " + iceConnectionState);
                        mUIThreadHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                                    if (null != mLocalVideoTrack) {
                                        //mLocalVideoTrack.setEnabled(false);
                                        //VideoRendererGui.remove(mLargeLocalRendererCallbacks);
                                        //mLocalVideoTrack.removeRenderer(mLargeLocalRenderer);
                                        //mLocalVideoTrack.addRenderer(mSmallLocalRenderer);
                                        //mLocalVideoTrack.setEnabled(true);
                                        mCallView.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (null != mCallView) {
                                                    mCallView.invalidate();
                                                }
                                            }
                                        });
                                    }
                                }
                                else if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                                    hangup("ice_failed");
                                }
                            }
                        });
                    }

                    @Override
                    public void onIceConnectionReceivingChange(boolean var1) {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onIceConnectionReceivingChange " + var1);
                    }

                    @Override
                    public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onIceGatheringChange " + iceGatheringState);
                    }

                    @Override
                    public void onIceCandidate(final IceCandidate iceCandidate) {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onIceCandidate " + iceCandidate);

                        sendCandidate(iceCandidate);
                    }

                    @Override
                    public void onAddStream(final MediaStream mediaStream) {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onAddStream " + mediaStream);
                        mUIThreadHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if ((mediaStream.videoTracks.size() == 1)) {
                                    mRemoteVideoTrack = mediaStream.videoTracks.get(0);
                                    mRemoteVideoTrack.setEnabled(true);
                                    mRemoteVideoTrack.addRenderer(mLargeRemoteRenderer);
                                }
                            }
                        });
                    }

                    @Override
                    public void onRemoveStream(final MediaStream mediaStream) {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onRemoveStream " + mediaStream);
                        mUIThreadHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (null != mRemoteVideoTrack) {
                                    mRemoteVideoTrack.dispose();
                                    mRemoteVideoTrack = null;
                                    mediaStream.videoTracks.get(0).dispose();
                                }
                            }
                        });
                    }

                    @Override
                    public void onDataChannel(DataChannel dataChannel) {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onDataChannel " + dataChannel);
                    }

                    @Override
                    public void onRenegotiationNeeded() {
                        Log.e(LOG_TAG, "## mPeerConnection creation: onRenegotiationNeeded");
                    }
                });

        // send our local video and audio stream to make it seen by the other part
        mPeerConnection.addStream(mLocalMediaStream);
    }

    private void destroyConnect(){
        if (null != mPeerConnection) {
            mPeerConnection.close();
            mPeerConnection = null;
        }

        if (null != mPeerConnectionFactory) {
            //mPeerConnectionFactory.dispose();
            //mPeerConnectionFactory = null;
        }
    }

    private void setRemoteDescription(final SessionDescription aDescription) {
        Log.e(LOG_TAG, "setRemoteDescription " + aDescription);

        mPeerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.e(LOG_TAG, "setRemoteDescription onCreateSuccess");
            }

            @Override
            public void onSetSuccess() {
                Log.e(LOG_TAG, "setRemoteDescription onSetSuccess");
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(LOG_TAG, "setRemoteDescription onCreateFailure " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(LOG_TAG, "setRemoteDescription onSetFailure " + s);
            }
        }, aDescription);
    }

    private boolean isSwitchCameraSupported() {
        return (VideoCapturerAndroid.getDeviceCount() > 1);
    }

    private boolean switchRearFrontCamera() {
        if ((null != mVideoCapturer) && (isSwitchCameraSupported())) {
            VideoCapturerAndroid videoCapturerAndroid = (VideoCapturerAndroid) mVideoCapturer;

            if (videoCapturerAndroid.switchCamera(null)) {
                return true;

            } else {
                Log.w(LOG_TAG, "## switchRearFrontCamera(): failed");
            }
        } else {
            Log.w(LOG_TAG, "## switchRearFrontCamera(): failure - invalid values");
        }
        return false;
    }


    private void callStart(){
        Log.e(LOG_TAG, "## callStart(): -> createOffer");

        MediaConstraints constraints = new MediaConstraints();
        //constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        // call createOffer only for outgoing calls
        mPeerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.e(LOG_TAG, "createOffer onCreateSuccess");
                final SessionDescription sdp = new SessionDescription(sessionDescription.type, sessionDescription.description);
                mUIThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mPeerConnection != null) {
                            // must be done to before sending the invitation message
                            mPeerConnection.setLocalDescription(new SdpObserver() {
                                @Override
                                public void onCreateSuccess(SessionDescription sessionDescription) {
                                    Log.e(LOG_TAG, "setLocalDescription onCreateSuccess");
                                }

                                @Override
                                public void onSetSuccess() {
                                    Log.e(LOG_TAG, "setLocalDescription onSetSuccess");
                                    sendInvite(sdp);
                                    mIsIncoming = false;
                                }

                                @Override
                                public void onCreateFailure(String s) {
                                    Log.e(LOG_TAG, "setLocalDescription onCreateFailure " + s);
                                    hangup(null);
                                }

                                @Override
                                public void onSetFailure(String s) {
                                    Log.e(LOG_TAG, "setLocalDescription onSetFailure " + s);
                                    hangup(null);
                                }
                            }, sdp);
                        }
                    }
                });
            }

            @Override
            public void onSetSuccess() {
                Log.e(LOG_TAG, "createOffer onSetSuccess");
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(LOG_TAG, "createOffer onCreateFailure " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(LOG_TAG, "createOffer onSetFailure " + s);
            }
        }, constraints);
    }

    private void sendInvite(final SessionDescription sessionDescription) {
        Log.e(LOG_TAG, "MXJingleCall sendInvite");
        Log.e(LOG_TAG,"sdp:" + sessionDescription.description + "type:" + sessionDescription.type.canonicalForm());

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        DhcpInfo info = wifiManager.getDhcpInfo();
        clientIP = Formatter.formatIpAddress(info.ipAddress);

        final JSONObject offerContent = new JSONObject();
        try {
            offerContent.put("name", "invite");
            offerContent.put("clientIP", clientIP);
            offerContent.put("sdp", sessionDescription.description);
            offerContent.put("type", sessionDescription.type.canonicalForm());
        } catch (JSONException e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
//                Socket s = null;
//                try {
//                    Log.e(LOG_TAG, "sendInvite ip:" + serverIP);
//                    s = new Socket(serverIP, mClientPort);
//                    String cmd = offerContent.toString();
//                    BufferedOutputStream os = new BufferedOutputStream(s.getOutputStream());
//                    os.write(cmd.getBytes(),0 , cmd.length());
//                    os.flush();
//                    os.close();
//                    s.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }

                sendPacket(serverIP, mClientPort, offerContent.toString());
            }
        }).start();
    }

    private void callAnswer() {
        Log.e(LOG_TAG, "answer");

        mUIThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                if (null == mPeerConnection) {
                    Log.e(LOG_TAG, "answer the connection has been closed");
                    return;
                }

                MediaConstraints constraints = new MediaConstraints();
                //constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
                mPeerConnection.createAnswer(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {
                        Log.e(LOG_TAG, "createAnswer onCreateSuccess");
                        final SessionDescription sdp = new SessionDescription(sessionDescription.type, sessionDescription.description);
                        mUIThreadHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mPeerConnection != null) {
                                    // must be done to before sending the invitation message
                                    mPeerConnection.setLocalDescription(new SdpObserver() {
                                        @Override
                                        public void onCreateSuccess(SessionDescription sessionDescription) {
                                            Log.e(LOG_TAG, "setLocalDescription onCreateSuccess");
                                        }

                                        @Override
                                        public void onSetSuccess() {
                                            Log.e(LOG_TAG, "setLocalDescription onSetSuccess");
                                            sendAnswer(sdp);
                                        }

                                        @Override
                                        public void onCreateFailure(String s) {
                                            Log.e(LOG_TAG, "setLocalDescription onCreateFailure " + s);
                                            hangup(null);
                                        }

                                        @Override
                                        public void onSetFailure(String s) {
                                            Log.e(LOG_TAG, "setLocalDescription onSetFailure " + s);
                                            hangup(null);
                                        }
                                    }, sdp);
                                }
                            }
                        });
                    }

                    @Override
                    public void onSetSuccess() {
                        Log.e(LOG_TAG, "createAnswer onSetSuccess");
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        Log.e(LOG_TAG, "createAnswer onCreateFailure " + s);
                        hangup(null);
                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(LOG_TAG, "createAnswer onSetFailure " + s);
                        hangup(null);
                    }
                }, constraints);
            }
        });
    }

    private void sendAnswer(final SessionDescription sessionDescription) {
        Log.e(LOG_TAG, "sendAnswer");

        Log.e(LOG_TAG,"sdp:" + sessionDescription.description + "type:" + sessionDescription.type.canonicalForm());
        final JSONObject offerContent = new JSONObject();
        try {
            offerContent.put("name", "answer");
            offerContent.put("sdp", sessionDescription.description);
            offerContent.put("type", sessionDescription.type.canonicalForm());
        } catch (JSONException e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
//                Socket s = null;
//                try {
//                    s = new Socket(clientIP, mClientPort);
//                    String cmd = offerContent.toString();
//                    BufferedOutputStream os = new BufferedOutputStream(s.getOutputStream());
//                    os.write(cmd.getBytes(),0 , cmd.length());
//                    os.flush();
//                    os.close();
//                    s.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }

                sendPacket(clientIP, mClientPort, offerContent.toString());
            }
        }).start();
    }

    private void sendCandidate(final IceCandidate iceCandidate){
        Log.e(LOG_TAG, "sendCandidate");

        Log.e(LOG_TAG,"sendCandidate sdpMLineIndex:" + iceCandidate.sdpMLineIndex + ":::sdpMid:" + iceCandidate.sdpMid + ":::sdp" + iceCandidate.sdp);
        final JSONObject candidate = new JSONObject();
        try {
            candidate.put("name", "iceCandidate");
            candidate.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            candidate.put("sdpMid", iceCandidate.sdpMid);
            candidate.put("sdp", iceCandidate.sdp);

        } catch (JSONException e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
//                Socket s = null;
//                try {
//                    String ip;
//                    if(mIsIncoming){
//                        ip = clientIP;
//                    }else{
//                        ip = serverIP;
//                    }
//                    Log.e(LOG_TAG, "sendCandidate ip:" + ip);
//                    s = new Socket(ip, mClientPort);
//                    String cmd = candidate.toString();
//                    BufferedOutputStream os = new BufferedOutputStream(s.getOutputStream());
//                    os.write(cmd.getBytes(),0 , cmd.length());
//                    os.flush();
//                    os.close();
//                    s.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }

                String ip;
                if(mIsIncoming){
                    ip = clientIP;
                }else{
                    ip = serverIP;
                }
                sendPacket(ip, mClientPort, candidate.toString());
            }
        }).start();
    }


    private void sendSwitchCamera(){
        final JSONObject candidate = new JSONObject();
        try {
            candidate.put("name", "switchCamera");
            candidate.put("cameraID", 0);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
//                Socket s = null;
//                try {
//                    String ip;
//                    if(mIsIncoming){
//                        ip = clientIP;
//                    }else{
//                        ip = serverIP;
//                    }
//                    Log.e(LOG_TAG, "sendCandidate sendSwitchCamera");
//                    s = new Socket(ip, mClientPort);
//                    String cmd = candidate.toString();
//                    BufferedOutputStream os = new BufferedOutputStream(s.getOutputStream());
//                    os.write(cmd.getBytes(),0 , cmd.length());
//                    os.flush();
//                    os.close();
//                    s.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }


                String ip;
                if(mIsIncoming){
                    ip = clientIP;
                }else{
                    ip = serverIP;
                }
                sendPacket(ip, mClientPort, candidate.toString());
            }
        }).start();
    }

    private synchronized  void sendPacket(String ip, int port, String packet){
        Log.e(LOG_TAG, "sendPacket  ip:" + ip + " port:" + port);
        Socket s = null;
        try {
            s = new Socket(ip, port);
            BufferedOutputStream os = new BufferedOutputStream(s.getOutputStream());
            os.write(packet.getBytes(),0 , packet.length());
            os.flush();
            os.close();
            s.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private void callServerStart(){
        Log.e(LOG_TAG, "callServerStart");
        final Boolean serverOn = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket(mServerPort);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                while(serverOn){

                    Socket s = new Socket();
                    try {
                        Log.e(LOG_TAG, "callServerStart listen");
                        //ss = new ServerSocket(mServerPort);
                        s = ss.accept();
                        Log.e(LOG_TAG, "callServerStart accept");
                        BufferedInputStream is = new BufferedInputStream(s.getInputStream()); // 读进
                        byte[] data = new byte[5000];// 每次读取的字节数
                        int len;
                        while(is.read(data) != -1){
                            SystemClock.sleep(10);
                        }

                        is.close();
                        s.close();
                        //ss.close();

                        String cmd = new String(data,"UTF-8");
                        cmd = cmd.trim();
                        Log.e(LOG_TAG, "callServerStart cmd:" + cmd);

                        JSONObject offerContent = new JSONObject(cmd);
                        String name = offerContent.getString("name");

                        if(name.equals("invite") || name.equals("answer")) {
                            Log.e(LOG_TAG, "callServerStart name:" + name);
                            String type = offerContent.getString("type");
                            String sdp = offerContent.getString("sdp");
                            Log.e(LOG_TAG, "sdp:" + sdp + "type:" + type);

                            if (type.equals("offer")) {
                                if (mCallState != 0) {
                                    destroyConnect();
                                    createLocalStream();
                                    createConnect();
                                }

                                clientIP = offerContent.getString("clientIP");
                                Log.e(LOG_TAG, "clientIP:" + clientIP);
                                SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.OFFER, sdp);
                                setRemoteDescription(sessionDescription);
                                callAnswer();
                                mCallState = 1;
                                mIsIncoming = true;
                            }
                            if (type.equals("answer")) {
                                SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
                                setRemoteDescription(sessionDescription);
                            }
                        }else if(name.equals("iceCandidate")){
                            Log.e(LOG_TAG, "callServerStart name:" + name);

                            String sdp = offerContent.getString("sdp");
                            String sdpMid = offerContent.getString("sdpMid");
                            int sdpLineIndex = offerContent.getInt("sdpMLineIndex");
                            Log.e(LOG_TAG,"callServerStart sdpMLineIndex:" + sdpLineIndex + ":::sdpMid:" + sdpMid + ":::sdp" + sdp);

                            mPeerConnection.addIceCandidate(new IceCandidate(sdpMid, sdpLineIndex, sdp));
                        }else if(name.equals("switchCamera")){
                            switchRearFrontCamera();
                        }


                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    SystemClock.sleep(100);
                }

                try {
                    ss.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void hangup(String reason) {
        Log.e(LOG_TAG, "## hangup(): reason=" + reason);
    }

    private boolean isIncoming() {
        return mIsIncoming;
    }

}
