/*Copyright (c) 2016, Andrew Walz.
 
 Redistribution and use in source and binary forms, with or without modification,are permitted provided that the following conditions are met:
 
 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 
 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 documentation and/or other materials provided with the distribution.
 
 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS
 BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import UIKit
import AVFoundation

// MARK: View Controller Declaration

/// A UIViewController Camera View Subclass

open class SwiftyCamViewController: UIViewController {
    
    // MARK: Enumeration Declaration
    
    /// Enumeration for Camera Selection
    
    public enum CameraSelection: String {
        
        /// Camera on the back of the device
        case rear = "rear"
        
        /// Camera on the front of the device
        case front = "front"
    }
    
    public enum FlashMode {
        // Return the equivalent AVCaptureDevice.FlashMode
        var AVFlashMode: AVCaptureDevice.FlashMode {
            switch self {
            case .on:
                return .on
            case .off:
                return .off
            case .auto:
                return .auto
            }
        }
        //Flash mode is set to auto
        case auto
        
        //Flash mode is set to on
        case on
        
        //Flash mode is set to off
        case off
    }
    
    /// Enumeration for video quality of the capture session. Corresponds to a AVCaptureSessionPreset
    
    
    public enum VideoQuality {
        
        /// AVCaptureSessionPresetHigh
        case high
        
        /// AVCaptureSessionPresetMedium
        case medium
        
        /// AVCaptureSessionPresetLow
        case low
        
        /// AVCaptureSessionPreset352x288
        case resolution352x288
        
        /// AVCaptureSessionPreset640x480
        case resolution640x480
        
        /// AVCaptureSessionPreset1280x720
        case resolution1280x720
        
        /// AVCaptureSessionPreset1920x1080
        case resolution1920x1080
        
        /// AVCaptureSessionPreset3840x2160
        case resolution3840x2160
        
        /// AVCaptureSessionPresetiFrame960x540
        case iframe960x540
        
        /// AVCaptureSessionPresetiFrame1280x720
        case iframe1280x720
    }
    
    /**
     
     Result from the AVCaptureSession Setup
     
     - success: success
     - notAuthorized: User denied access to Camera of Microphone
     - configurationFailed: Unknown error
     */
    
    fileprivate enum SessionSetupResult {
        case success
        case notAuthorized
        case configurationFailed
    }
    
    // MARK: Public Variable Declarations
    
    /// Public Camera Delegate for the Custom View Controller Subclass
    
    public weak var cameraDelegate: SwiftyCamViewControllerDelegate?
    
    /// Maxiumum video duration if SwiftyCamButton is used
    
    public var maximumVideoDuration : Double     = 0.0
    
    /// Video capture quality
    
    public var videoQuality : VideoQuality       = .high
    
    /// Sets whether flash is enabled for photo and video capture
    @available(*, deprecated, message: "use flashMode .on or .off") //use flashMode
    public var flashEnabled: Bool = false {
        didSet{
            self.flashMode = self.flashEnabled ? .on : .off
        }
    }
    
    // Flash Mode
    public var flashMode:FlashMode               = .off
    
    /// Sets whether Pinch to Zoom is enabled for the capture session
    
    public var pinchToZoom                       = false
    
    /// Sets the maximum zoom scale allowed during gestures gesture
    
    public var maxZoomScale				         = CGFloat.greatestFiniteMagnitude
    
    /// Sets whether Tap to Focus and Tap to Adjust Exposure is enabled for the capture session
    
    public var tapToFocus                        = true
    
    /// Sets whether the capture session should adjust to low light conditions automatically
    ///
    /// Only supported on iPhone 5 and 5C
    
    public var lowLightBoost                     = true
    
    /// Set whether SwiftyCam should allow background audio from other applications
    
    public var allowBackgroundAudio              = true
    
    /// Sets whether a double tap to switch cameras is supported
    
    public var doubleTapCameraSwitch            = false
    
    /// Sets whether swipe vertically to zoom is supported
    
    public var swipeToZoom                     = false
    
    /// Sets whether swipe vertically gestures should be inverted
    
    public var swipeToZoomInverted             = false
    
    /// Set default launch camera
    
    public var defaultCamera                   = CameraSelection.rear
    
    /// Sets wether the taken photo or video should be oriented according to the device orientation
    
    public var shouldUseDeviceOrientation      = false {
        didSet {
            orientation.shouldUseDeviceOrientation = shouldUseDeviceOrientation
        }
    }
    
    /// Sets whether or not View Controller supports auto rotation
    
    public var allowAutoRotate                = false
    
    /// 其实最好的是 .resizeAspect
    /// Specifies the [videoGravity](https://developer.apple.com/reference/avfoundation/avcapturevideopreviewlayer/1386708-videogravity) for the preview layer.
    public var videoGravity                   : SwiftyCamVideoGravity = .resizeAspect
    
    /// Sets whether or not video recordings will record audio
    /// Setting to true will prompt user for access to microphone on View Controller launch.
    public var audioEnabled                   = true
    
    /// Sets whether or not app should display prompt to app settings if audio/video permission is denied
    /// If set to false, delegate function will be called to handle exception
    public var shouldPrompToAppSettings       = true
    
    /// Video will be recorded to this folder
    public var outputFolder: String           = NSTemporaryDirectory()
    
    /// Public access to Pinch Gesture
    fileprivate(set) public var pinchGesture  : UIPinchGestureRecognizer!
    
    /// Public access to Pan Gesture
    fileprivate(set) public var panGesture    : UIPanGestureRecognizer!
    
    
    // MARK: Public Get-only Variable Declarations
    
    /// Returns true if video is currently being recorded
    
    private(set) public var isVideoRecording      = false
    
    /// Returns true if the capture session is currently running
    
    private(set) public var isSessionRunning     = false
    
    /// Returns the CameraSelection corresponding to the currently utilized camera
    
    private(set) public var currentCamera        = CameraSelection.rear
    
    // MARK: Private Constant Declarations
    
    /// Current Capture Session
    
    public let session                           = AVCaptureSession()
    
    /// Serial queue used for setting up session
    
    fileprivate let sessionQueue                 = DispatchQueue(label: "session queue", attributes: [])
    
    // MARK: Private Variable Declarations
    
    /// Variable for storing current zoom scale
    
    fileprivate var zoomScale                    = CGFloat(1.0)
    
    /// Variable for storing initial zoom scale before Pinch to Zoom begins
    
    fileprivate var beginZoomScale               = CGFloat(1.0)
    
    /// Returns true if the torch (flash) is currently enabled
    
    fileprivate var isCameraTorchOn              = false
    
    /// Variable to store result of capture session setup
    
    fileprivate var setupResult                  = SessionSetupResult.success
    
    /// BackgroundID variable for video recording
    
    fileprivate var backgroundRecordingID        : UIBackgroundTaskIdentifier? = nil
    
    /// Video Input variable
    
    fileprivate var videoDeviceInput             : AVCaptureDeviceInput!
    
    /// Movie File Output variable
    
    fileprivate var movieFileOutput              : AVCaptureMovieFileOutput?
    
    /// Photo File Output variable
    
    fileprivate var photoFileOutput              : AVCaptureStillImageOutput?
    
    /// 帧采样 @sgl
    fileprivate var sampleBufferOutput           : AVCaptureVideoDataOutput?
    
    /// @sgl 外部业务直接取这张图就是抽到的帧了
    public var capturedFrameImage                : UIImage?
    
    /// @sgl 预览实时抽到的帧
    public var captureFrameImageView             : UIImageView?

    /// Video Device variable
    
    fileprivate var videoDevice                  : AVCaptureDevice?
    
    /// PreviewView for the capture session
    
    fileprivate var previewLayer                 : SwiftyCameraPreviewView!
    
    /// UIView for front facing flash
    
    fileprivate var flashView                    : UIView?
    
    /// Pan Translation
    
    fileprivate var previousPanTranslation       : CGFloat = 0.0
    
    /// Last changed orientation
    
    fileprivate var orientation                  : SwiftyCameraOrientation = SwiftyCameraOrientation()
    
    /// Boolean to store when View Controller is notified session is running
    
    fileprivate var sessionRunning               = false
    
    /// Disable view autorotation for forced portrait recorindg
    
    override open var shouldAutorotate: Bool {
        return allowAutoRotate
    }
    
    /// Sets output video codec
    
    public var videoCodecType: AVVideoCodecType? = nil
    
    /// 外部设备？
    public static var externalDevice = false
    
    // MARK: ViewDidLoad
    
    /// ViewDidLoad Implementation
    
    override open func viewDidLoad() {
        super.viewDidLoad()
        
        self.view.backgroundColor = .black
        
        // 让画面拉伸
        videoGravity = .resizeAspectFill
        
        previewLayer = SwiftyCameraPreviewView(frame: view.frame, videoGravity: videoGravity)
        previewLayer.isHidden = true
        previewLayer.center = view.center
        view.addSubview(previewLayer)
        view.sendSubviewToBack(previewLayer)
        
        // Add Gesture Recognizers
        
        addGestureRecognizers()
        
        previewLayer.session = session
        
        // Test authorization status for Camera and Micophone
        switch AVCaptureDevice.authorizationStatus(for: AVMediaType.video) {
        case .authorized:
            // already authorized
            print("[SwiftyCam]: Camera permission already authorized")
            break
        case .notDetermined:
            
            // not yet determined
            sessionQueue.suspend()
            
            // 申请权限
            AVCaptureDevice.requestAccess(for: AVMediaType.video, completionHandler: { [unowned self] granted in
                if !granted {
                    self.setupResult = .notAuthorized
                    print("[SwiftyCam]: Camera permission denied by user")
                    
                    // 用户不给权限的话，直接返回到上一层
                    DispatchQueue.main.async {
                        self.dismiss(animated: true)
                    }
                } else {
                    print("[SwiftyCam]: Camera permission granted by user")
                }
                self.sessionQueue.resume()
            })
            
        default:
            // already been asked. Denied access
            setupResult = .notAuthorized
            print("[SwiftyCam]: Camera permission previously denied")
        }
        
        sessionQueue.async { [unowned self] in
            self.configureSession()
        }
    }
    
    // MARK: ViewDidLayoutSubviews
    
    /// ViewDidLayoutSubviews() Implementation
    private func updatePreviewLayer(layer: AVCaptureConnection, orientation: AVCaptureVideoOrientation) {
        
        if(shouldAutorotate) {
            layer.videoOrientation = orientation
        } else {
            layer.videoOrientation = .portrait
        }
        
        previewLayer.frame = self.view.bounds
    }
    
    override open func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        
        if let connection = self.previewLayer?.videoPreviewLayer.connection {
            
            let currentDevice: UIDevice = UIDevice.current
            
            var orientation: UIDeviceOrientation = currentDevice.orientation
            
            // 外部设备
            if SwiftyCamViewController.externalDevice {
                orientation = .portraitUpsideDown
            }
            
            let previewLayerConnection : AVCaptureConnection = connection
            
            if previewLayerConnection.isVideoOrientationSupported {
                
                switch (orientation) {
                case .portrait:
                        updatePreviewLayer(layer: previewLayerConnection, orientation: .portrait)
                    break
                    
                case .landscapeRight:
                        updatePreviewLayer(layer: previewLayerConnection, orientation: .landscapeLeft)
                    break
                    
                case .landscapeLeft:
                        updatePreviewLayer(layer: previewLayerConnection, orientation: .landscapeRight)
                    break
                    
                case .portraitUpsideDown:
                        updatePreviewLayer(layer: previewLayerConnection, orientation: .portraitUpsideDown)
                    break
                    
                default:
                        updatePreviewLayer(layer: previewLayerConnection, orientation: .portrait)
                    break
                }
            }
        }
    }
    
    // MARK: ViewWillAppear
    
    /// ViewWillAppear(_ animated:) Implementation
    
    open override func viewWillAppear(_ animated: Bool) {
        
        super.viewWillAppear(animated)
        
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(captureSessionDidStartRunning),
                                               name: Notification.Name("AVCaptureSessionDidStartRunningNotification"),
                                               object: nil)
        
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(captureSessionDidStopRunning),
                                               name: Notification.Name("AVCaptureSessionDidStopRunningNotification"),
                                               object: nil)
    }
    
    // MARK: ViewDidAppear
    
    /// ViewDidAppear(_ animated:) Implementation
    override open func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        
        // Subscribe to device rotation notifications
        
        if shouldUseDeviceOrientation {
            orientation.start()
        }
        
        // Set background audio preference
        
        setBackgroundAudioPreference()
        
        sessionQueue.async {
            switch self.setupResult {
            case .success:
                // Begin Session
                print("[SwiftyCam]: Starting capture session...")
                self.session.startRunning()
                self.isSessionRunning = self.session.isRunning
                print("[SwiftyCam]: Session is running: \(self.isSessionRunning)")
                
                // Preview layer video orientation can be set only after the connection is created
                DispatchQueue.main.async {
                    self.previewLayer.videoPreviewLayer.connection?.videoOrientation = self.orientation.getPreviewLayerOrientation()
                    
                    // todo: @sgl 尽可能放在 preview 区域，和原生相机差不多一样大
                    // self.currentCamera = .front
                    // self.switchCamera()
                }
                
            case .notAuthorized:
                print("[SwiftyCam]: Setup failed - not authorized")
                if self.shouldPrompToAppSettings == true {
                    self.promptToAppSettings()
                } else {
                    self.cameraDelegate?.swiftyCamNotAuthorized(self)
                }
            case .configurationFailed:
                // Unknown Error
                print("[SwiftyCam]: Setup failed - configuration failed")
                DispatchQueue.main.async {
                    self.cameraDelegate?.swiftyCamDidFailToConfigure(self)
                }
            }
        }
    }
    
    // MARK: ViewDidDisappear
    
    /// ViewDidDisappear(_ animated:) Implementation
    override open func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        
        NotificationCenter.default.removeObserver(self)
        sessionRunning = false
        
        // If session is running, stop the session
        if self.isSessionRunning == true {
            self.session.stopRunning()
            self.isSessionRunning = false
        }
        
        //Disble flash if it is currently enabled
        disableFlash()
        
        // Unsubscribe from device rotation notifications
        if shouldUseDeviceOrientation {
            orientation.stop()
        }
    }
    
    // MARK: Public Functions
    
    /**
     
     Capture photo from current session
     
     UIImage will be returned with the SwiftyCamViewControllerDelegate function SwiftyCamDidTakePhoto(photo:)
     
     */
    
    public func takePhoto() {
        
        guard let device = videoDevice else {
            return
        }
        
        if device.hasFlash == true && flashMode != .off /* TODO: Add Support for Retina Flash and add front flash */ {
            changeFlashSettings(device: device, mode: flashMode)
            capturePhotoAsyncronously(completionHandler: { (_) in })
        } else {
            if device.isFlashActive == true {
                changeFlashSettings(device: device, mode: flashMode)
            }
            
            capturePhotoAsyncronously(completionHandler: { (_) in })
        }
    }
    
    /**
     
     Begin recording video of current session
     
     SwiftyCamViewControllerDelegate function SwiftyCamDidBeginRecordingVideo() will be called
     
     */
    
    public func startVideoRecording() {
        
        guard sessionRunning == true else {
            print("[SwiftyCam]: Cannot start video recoding. Capture session is not running")
            return
        }
        guard let movieFileOutput = self.movieFileOutput else {
            return
        }
        
        if currentCamera == .rear && flashMode == .on {
            enableFlash()
        }
        
        if currentCamera == .front && flashMode == .on  {
            flashView = UIView(frame: view.frame)
            flashView?.backgroundColor = UIColor.white
            flashView?.alpha = 0.85
            previewLayer.addSubview(flashView!)
        }
        
        // Must be fetched before on main thread
        let previewOrientation = previewLayer.videoPreviewLayer.connection!.videoOrientation
        
        sessionQueue.async { [unowned self] in
            if !movieFileOutput.isRecording {
                if UIDevice.current.isMultitaskingSupported {
                    self.backgroundRecordingID = UIApplication.shared.beginBackgroundTask(expirationHandler: nil)
                }
                
                // Update the orientation on the movie file output video connection before starting recording.
                let movieFileOutputConnection = self.movieFileOutput?.connection(with: AVMediaType.video)
                
                // flip video output if front facing camera is selected
                if self.currentCamera == .front {
                    movieFileOutputConnection?.isVideoMirrored = true
                }
                
                movieFileOutputConnection?.videoOrientation = self.orientation.getVideoOrientation() ?? previewOrientation
                
                // 外部设备
                if SwiftyCamViewController.externalDevice {
//                     movieFileOutputConnection?.videoOrientation = .portraitUpsideDown
                }
                
                // Start recording to a temporary file.
                let outputFileName = UUID().uuidString
                let outputFilePath = (self.outputFolder as NSString).appendingPathComponent((outputFileName as NSString).appendingPathExtension("mov")!)
                movieFileOutput.startRecording(to: URL(fileURLWithPath: outputFilePath), recordingDelegate: self)
                self.isVideoRecording = true
                DispatchQueue.main.async {
                    self.cameraDelegate?.swiftyCam(self, didBeginRecordingVideo: self.currentCamera)
                }
            }
            else {
                movieFileOutput.stopRecording()
            }
        }
    }
    
    /**
     
     Stop video recording video of current session
     
     SwiftyCamViewControllerDelegate function SwiftyCamDidFinishRecordingVideo() will be called
     
     When video has finished processing, the URL to the video location will be returned by SwiftyCamDidFinishProcessingVideoAt(url:)
     
     */
    
    public func stopVideoRecording() {
        if self.isVideoRecording == true {
            self.isVideoRecording = false
            movieFileOutput!.stopRecording()
            disableFlash()
            
            if currentCamera == .front && flashMode == .on && flashView != nil {
                UIView.animate(withDuration: 0.1, delay: 0.0, options: .curveEaseInOut, animations: {
                    self.flashView?.alpha = 0.0
                }, completion: { (_) in
                    self.flashView?.removeFromSuperview()
                })
            }
            DispatchQueue.main.async {
                self.cameraDelegate?.swiftyCam(self, didFinishRecordingVideo: self.currentCamera)
            }
        }
    }
    
    /**
     
     Switch between front and rear camera
     
     SwiftyCamViewControllerDelegate function SwiftyCamDidSwitchCameras(camera:  will be return the current camera selection
     
     */
    
    public func switchCamera() {
        guard isVideoRecording != true else {
            //TODO: Look into switching camera during video recording
            print("[SwiftyCam]: Switching between cameras while recording video is not supported")
            return
        }
        
        guard session.isRunning == true else {
            return
        }
        
        switch currentCamera {
        case .front:
            currentCamera = .rear
        case .rear:
            currentCamera = .front
        }
        
        session.stopRunning()
        
        sessionQueue.async { [unowned self] in
            
            // remove and re-add inputs and outputs
            
            for input in self.session.inputs {
                self.session.removeInput(input )
            }
            
            self.addInputs()
            DispatchQueue.main.async {
                self.cameraDelegate?.swiftyCam(self, didSwitchCameras: self.currentCamera)
            }
            
            self.session.startRunning()
        }
        
        // If flash is enabled, disable it as the torch is needed for front facing camera
        disableFlash()
    }
    
    // MARK: Private Functions
    
    /// Configure session, add inputs and outputs
    
    fileprivate func configureSession() {
        guard setupResult == .success else {
            return
        }
        
        // Set default camera
        
        currentCamera = defaultCamera
        
        // begin configuring session
        
        session.beginConfiguration()
        configureVideoPreset()
        addVideoInput()
        addAudioInput()
        configureVideoOutput()
        configurePhotoOutput()
        
        // @sgl sample buffer
        configureSampleBufferOutput()
        
        session.commitConfiguration()
    }
    
    /// Add inputs after changing camera()
    
    fileprivate func addInputs() {
        session.beginConfiguration()
        configureVideoPreset()
        addVideoInput()
        addAudioInput()
        session.commitConfiguration()
    }
    
    
    // Front facing camera will always be set to VideoQuality.high
    // If set video quality is not supported, videoQuality variable will be set to VideoQuality.high
    /// Configure image quality preset
    
    fileprivate func configureVideoPreset() {
        if currentCamera == .front {
            session.sessionPreset = AVCaptureSession.Preset(rawValue: videoInputPresetFromVideoQuality(quality: .high))
        } else {
            if session.canSetSessionPreset(AVCaptureSession.Preset(rawValue: videoInputPresetFromVideoQuality(quality: videoQuality))) {
                session.sessionPreset = AVCaptureSession.Preset(rawValue: videoInputPresetFromVideoQuality(quality: videoQuality))
            } else {
                session.sessionPreset = AVCaptureSession.Preset(rawValue: videoInputPresetFromVideoQuality(quality: .high))
            }
        }
    }
    
    /// Add Video Inputs
    
    fileprivate func addVideoInput() {
        switch currentCamera {
        case .front:
            videoDevice = SwiftyCamViewController.deviceWithMediaType(AVMediaType.video.rawValue, preferringPosition: .front)
        case .rear:
            videoDevice = SwiftyCamViewController.deviceWithMediaType(AVMediaType.video.rawValue, preferringPosition: .back)
        }
        
        if let device = videoDevice {
            do {
                try device.lockForConfiguration()
                if device.isFocusModeSupported(.continuousAutoFocus) {
                    device.focusMode = .continuousAutoFocus
                    if device.isSmoothAutoFocusSupported {
                        device.isSmoothAutoFocusEnabled = true
                    }
                }
                
                if device.isExposureModeSupported(.continuousAutoExposure) {
                    device.exposureMode = .continuousAutoExposure
                }
                
                if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) {
                    device.whiteBalanceMode = .continuousAutoWhiteBalance
                }
                
                if device.isLowLightBoostSupported && lowLightBoost == true {
                    device.automaticallyEnablesLowLightBoostWhenAvailable = true
                }
                
                device.unlockForConfiguration()
            } catch {
                print("[SwiftyCam]: Error locking configuration")
            }
        }
        
        do {
            if let videoDevice = videoDevice {
                let videoDeviceInput = try AVCaptureDeviceInput(device: videoDevice)
                if session.canAddInput(videoDeviceInput) {
                    session.addInput(videoDeviceInput)
                    self.videoDeviceInput = videoDeviceInput
                } else {
                    print("[SwiftyCam]: Could not add video device input to the session")
                    print(session.canSetSessionPreset(AVCaptureSession.Preset(rawValue: videoInputPresetFromVideoQuality(quality: videoQuality))))
                    setupResult = .configurationFailed
                    session.commitConfiguration()
                    return
                }
            }
            
        } catch {
            print("[SwiftyCam]: Could not create video device input: \(error)")
            setupResult = .configurationFailed
            return
        }
    }
    
    /// Add Audio Inputs
    
    fileprivate func addAudioInput() {
        guard audioEnabled == true else {
            return
        }
        do {
            if let audioDevice = AVCaptureDevice.default(for: AVMediaType.audio){
                let audioDeviceInput = try AVCaptureDeviceInput(device: audioDevice)
                if session.canAddInput(audioDeviceInput) {
                    session.addInput(audioDeviceInput)
                } else {
                    print("[SwiftyCam]: Could not add audio device input to the session")
                }
                
            } else {
                print("[SwiftyCam]: Could not find an audio device")
            }
            
        } catch {
            print("[SwiftyCam]: Could not create audio device input: \(error)")
        }
    }
    
    /// Configure Movie Output
    
    fileprivate func configureVideoOutput() {
        let movieFileOutput = AVCaptureMovieFileOutput()
        
        if self.session.canAddOutput(movieFileOutput) {
            self.session.addOutput(movieFileOutput)
            if let connection = movieFileOutput.connection(with: AVMediaType.video) {
                if connection.isVideoStabilizationSupported {
                    connection.preferredVideoStabilizationMode = .auto
                }
                
                if #available(iOS 11.0, *) {
                    if let videoCodecType = videoCodecType {
                        if movieFileOutput.availableVideoCodecTypes.contains(videoCodecType) == true {
                            // Use the H.264 codec to encode the video.
                            movieFileOutput.setOutputSettings([AVVideoCodecKey: videoCodecType], for: connection)
                        }
                    }
                }
            }
            self.movieFileOutput = movieFileOutput
        }
    }
    
    /// Configure Photo Output
    
    fileprivate func configurePhotoOutput() {
        let photoFileOutput = AVCaptureStillImageOutput()
        
        if self.session.canAddOutput(photoFileOutput) {
            photoFileOutput.outputSettings = [AVVideoCodecKey: AVVideoCodecJPEG]
            self.session.addOutput(photoFileOutput)
            self.photoFileOutput = photoFileOutput
        }
    }
    
    /// @sgl Configure SampleBuffer Output
    
    fileprivate func configureSampleBufferOutput() {
        // 这个 output 可以抽取帧到一个回调方法里
        let videoOutput = AVCaptureVideoDataOutput()
        videoOutput.setSampleBufferDelegate(self, queue: DispatchQueue(label: "videoQueue"))
        session.addOutput(videoOutput)
    }
    
    /**
     Returns a UIImage from Image Data.
     
     - Parameter imageData: Image Data returned from capturing photo from the capture session.
     
     - Returns: UIImage from the image data, adjusted for proper orientation.
     */
    
    fileprivate func processPhoto(_ imageData: Data) -> UIImage {
        let dataProvider = CGDataProvider(data: imageData as CFData)
        let cgImageRef = CGImage(jpegDataProviderSource: dataProvider!, decode: nil, shouldInterpolate: true, intent: CGColorRenderingIntent.defaultIntent)
        
        // Set proper orientation for photo
        // If camera is currently set to front camera, flip image
        
        let image = UIImage(cgImage: cgImageRef!, scale: 1.0, orientation: self.orientation.getImageOrientation(forCamera: self.currentCamera))
        
        return image
    }
    
    fileprivate func capturePhotoAsyncronously(completionHandler: @escaping(Bool) -> ()) {
        
        guard sessionRunning == true else {
            print("[SwiftyCam]: Cannot take photo. Capture session is not running")
            return
        }
        
        if let videoConnection = photoFileOutput?.connection(with: AVMediaType.video) {
            
            photoFileOutput?.captureStillImageAsynchronously(from: videoConnection, completionHandler: {(sampleBuffer, error) in
                if (sampleBuffer != nil) {
                    let imageData = AVCaptureStillImageOutput.jpegStillImageNSDataRepresentation(sampleBuffer!)
                    var image = self.processPhoto(imageData!)
                    
                    // 外部设备
                    if SwiftyCamViewController.externalDevice {
                        image = image.rotated(byDegrees: 180) ?? image
                    }
                    
                    // Call delegate and return new image
                    DispatchQueue.main.async {
                        self.cameraDelegate?.swiftyCam(self, didTake: image)
                    }
                    completionHandler(true)
                } else {
                    completionHandler(false)
                }
            })
        } else {
            completionHandler(false)
        }
    }
    
    /// Handle Denied App Privacy Settings
    
    fileprivate func promptToAppSettings() {
        // prompt User with UIAlertView
        
        DispatchQueue.main.async(execute: { [unowned self] in
            let message = NSLocalizedString("应用程序未能被允许使用相机, 请到设置页手工允许相机权限后再试。",
                                            comment: "Alert message when the user has denied access to the camera")
            
            let alertController = UIAlertController(title: "提示",
                                                    message: message,
                                                    preferredStyle: .alert)
            
            alertController.addAction(UIAlertAction(title: NSLocalizedString("好", comment: "Alert OK button"),
                                                    style: .cancel,
                                                    handler: nil))
            
            alertController.addAction(UIAlertAction(title: NSLocalizedString("去开启",
                                                                             comment: "Alert button to open Settings"),
                                                    style: .default,
                                                    handler: { action in
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                }
            }))
            
            self.present(alertController, animated: true, completion: nil)
        })
    }
    
    /**
     Returns an AVCapturePreset from VideoQuality Enumeration
     
     - Parameter quality: ViewQuality enum
     
     - Returns: String representing a AVCapturePreset
     */
    
    fileprivate func videoInputPresetFromVideoQuality(quality: VideoQuality) -> String {
        switch quality {
        case .high: return AVCaptureSession.Preset.high.rawValue
        case .medium: return AVCaptureSession.Preset.medium.rawValue
        case .low: return AVCaptureSession.Preset.low.rawValue
        case .resolution352x288: return AVCaptureSession.Preset.cif352x288.rawValue
        case .resolution640x480: return AVCaptureSession.Preset.vga640x480.rawValue
        case .resolution1280x720: return AVCaptureSession.Preset.hd1280x720.rawValue
        case .resolution1920x1080: return AVCaptureSession.Preset.hd1920x1080.rawValue
        case .iframe960x540: return AVCaptureSession.Preset.iFrame960x540.rawValue
        case .iframe1280x720: return AVCaptureSession.Preset.iFrame1280x720.rawValue
        case .resolution3840x2160:
            if #available(iOS 9.0, *) {
                return AVCaptureSession.Preset.hd4K3840x2160.rawValue
            }
            else {
                print("[SwiftyCam]: Resolution 3840x2160 not supported")
                return AVCaptureSession.Preset.high.rawValue
            }
        }
    }
    
    /// Get Devices
    
    fileprivate class func deviceWithMediaType(_ mediaType: String, preferringPosition position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        if #available(iOS 10.0, *) {
            // 原来是这儿 builtInWideAngleCamera
            var avDevice = AVCaptureDevice.default(AVCaptureDevice.DeviceType.builtInWideAngleCamera, for: AVMediaType(rawValue: mediaType), position: position)
            
            // 如果内置设置失败后，就可以尝试加载外部 usb camera
            if avDevice == nil {
                if #available(iOS 17.0, *) {
                    avDevice = AVCaptureDevice.default(AVCaptureDevice.DeviceType.external, for: AVMediaType(rawValue: mediaType), position: position)
                    SwiftyCamViewController.externalDevice = true
                } else {
                    // Fallback on earlier versions
                }
            }

            // 注释掉强制使用外部设备的调试代码，避免在真机上出现问题
            // #if DEBUG
            // if #available(iOS 17.0, *) {
            //     avDevice = AVCaptureDevice.default(AVCaptureDevice.DeviceType.external, for: AVMediaType(rawValue: mediaType), position: position)
            //     SwiftyCamViewController.externalDevice = true
            // } else {
            //     // Fallback on earlier versions
            // }
            // #endif
            
            return avDevice
        } else {
            // Fallback on earlier versions
            let avDevice = AVCaptureDevice.devices(for: AVMediaType(rawValue: mediaType))
            var avDeviceNum = 0
            for device in avDevice {
                print("deviceWithMediaType Position: \(device.position.rawValue)")
                if device.position == position {
                    break
                } else {
                    avDeviceNum += 1
                }
            }
            
            return avDevice[avDeviceNum]
        }
    }
    
    /// Enable or disable flash for photo
    
    fileprivate func changeFlashSettings(device: AVCaptureDevice, mode: FlashMode) {
        do {
            try device.lockForConfiguration()
            device.flashMode = mode.AVFlashMode
            device.unlockForConfiguration()
        } catch {
            print("[SwiftyCam]: \(error)")
        }
    }
    
    /// Enable flash
    
    fileprivate func enableFlash() {
        if self.isCameraTorchOn == false {
            toggleFlash()
        }
    }
    
    /// Disable flash
    
    fileprivate func disableFlash() {
        if self.isCameraTorchOn == true {
            toggleFlash()
        }
    }
    
    /// Toggles between enabling and disabling flash
    
    fileprivate func toggleFlash() {
        guard self.currentCamera == .rear else {
            // Flash is not supported for front facing camera
            return
        }
        
        let device = AVCaptureDevice.default(for: AVMediaType.video)
        // Check if device has a flash
        if (device?.hasTorch)! {
            do {
                try device?.lockForConfiguration()
                if (device?.torchMode == AVCaptureDevice.TorchMode.on) {
                    device?.torchMode = AVCaptureDevice.TorchMode.off
                    self.isCameraTorchOn = false
                } else {
                    do {
                        try device?.setTorchModeOn(level: 1.0)
                        self.isCameraTorchOn = true
                    } catch {
                        print("[SwiftyCam]: \(error)")
                    }
                }
                device?.unlockForConfiguration()
            } catch {
                print("[SwiftyCam]: \(error)")
            }
        }
    }
    
    /// Sets whether SwiftyCam should enable background audio from other applications or sources
    
    fileprivate func setBackgroundAudioPreference() {
        guard allowBackgroundAudio == true else {
            return
        }
        
        guard audioEnabled == true else {
            return
        }
        
        do{
            if #available(iOS 10.0, *) {
                try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .allowBluetooth, .allowAirPlay, .allowBluetoothA2DP])
            } else {
                let options: [AVAudioSession.CategoryOptions] = [.mixWithOthers, .allowBluetooth]
                let category = AVAudioSession.Category.playAndRecord
                let selector = NSSelectorFromString("setCategory:withOptions:error:")
                AVAudioSession.sharedInstance().perform(selector, with: category, with: options)
            }
            try AVAudioSession.sharedInstance().setActive(true)
            session.automaticallyConfiguresApplicationAudioSession = false
        }
        catch {
            print("[SwiftyCam]: Failed to set background audio preference")
        }
    }
    
    /// Called when Notification Center registers session starts running
    
    @objc private func captureSessionDidStartRunning() {
        sessionRunning = true
        DispatchQueue.main.async {
            self.cameraDelegate?.swiftyCamSessionDidStartRunning(self)

            // session ready 后再显示出来
            self.previewLayer.isHidden = false
            
            // 添加调试信息
            print("[SwiftyCam]: Session started running, preview layer is now visible")
        }
    }
    
    /// Called when Notification Center registers session stops running
    
    @objc private func captureSessionDidStopRunning() {
        sessionRunning = false
        DispatchQueue.main.async {
            self.cameraDelegate?.swiftyCamSessionDidStopRunning(self)
        }
    }
}

extension SwiftyCamViewController : SwiftyCamButtonDelegate {
    
    /// Sets the maximum duration of the SwiftyCamButton
    
    public func setMaxiumVideoDuration() -> Double {
        return maximumVideoDuration
    }
    
    /// Set UITapGesture to take photo
    
    public func buttonWasTapped() {
        // TODO: @sgl 不需要拍照功能 takePhoto()
        // 改为录像功能
        if isVideoRecording == false {
            startVideoRecording()
        } else {
            stopVideoRecording()
        }
    }
    
    /// Set UILongPressGesture start to begin video
    
    public func buttonDidBeginLongPress() {
        // startVideoRecording()
    }
    
    /// Set UILongPressGesture begin to begin end video
    
    
    public func buttonDidEndLongPress() {
        stopVideoRecording()
    }
    
    /// Called if maximum duration is reached
    
    public func longPressDidReachMaximumDuration() {
        stopVideoRecording()
    }
}

// MARK: AVCaptureFileOutputRecordingDelegate

extension SwiftyCamViewController : AVCaptureFileOutputRecordingDelegate {
    
    /// Process newly captured video and write it to temporary directory
    
    public func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        if let currentBackgroundRecordingID = backgroundRecordingID {
            backgroundRecordingID = UIBackgroundTaskIdentifier.invalid
            
            if currentBackgroundRecordingID != UIBackgroundTaskIdentifier.invalid {
                UIApplication.shared.endBackgroundTask(currentBackgroundRecordingID)
            }
        }
        
        if let currentError = error {
            print("[SwiftyCam]: Movie file finishing error: \(currentError)")
            DispatchQueue.main.async {
                self.cameraDelegate?.swiftyCam(self, didFailToRecordVideo: currentError)
            }
        } else {
            // Call delegate function with the URL of the outputfile
            DispatchQueue.main.async {
                self.cameraDelegate?.swiftyCam(self, didFinishProcessVideoAt: outputFileURL)
            }
        }
    }
}

// Mark: UIGestureRecognizer Declarations

extension SwiftyCamViewController {
    
    /// Handle pinch gesture
    
    @objc fileprivate func zoomGesture(pinch: UIPinchGestureRecognizer) {
        guard pinchToZoom == true && self.currentCamera == .rear else {
            // ignore pinch
            return
        }
        do {
            let captureDevice = AVCaptureDevice.devices().first
            try captureDevice?.lockForConfiguration()
            
            zoomScale = min(maxZoomScale, max(1.0, min(beginZoomScale * pinch.scale,  captureDevice!.activeFormat.videoMaxZoomFactor)))
            
            captureDevice?.videoZoomFactor = zoomScale
            
            // Call Delegate function with current zoom scale
            DispatchQueue.main.async {
                self.cameraDelegate?.swiftyCam(self, didChangeZoomLevel: self.zoomScale)
            }
            
            captureDevice?.unlockForConfiguration()
            
        } catch {
            print("[SwiftyCam]: Error locking configuration")
        }
    }
    
    /// Handle single tap gesture
    
    @objc fileprivate func singleTapGesture(tap: UITapGestureRecognizer) {
        guard tapToFocus == true else {
            // Ignore taps
            return
        }
        
        let screenSize = previewLayer!.bounds.size
        let tapPoint = tap.location(in: previewLayer!)
        let x = tapPoint.y / screenSize.height
        let y = 1.0 - tapPoint.x / screenSize.width
        let focusPoint = CGPoint(x: x, y: y)
        
        if let device = videoDevice {
            do {
                try device.lockForConfiguration()
                
                if device.isFocusPointOfInterestSupported == true {
                    device.focusPointOfInterest = focusPoint
                    device.focusMode = .autoFocus
                }
                device.exposurePointOfInterest = focusPoint
                device.exposureMode = AVCaptureDevice.ExposureMode.continuousAutoExposure
                device.unlockForConfiguration()
                // Call delegate function and pass in the location of the touch
                
                DispatchQueue.main.async {
                    self.cameraDelegate?.swiftyCam(self, didFocusAtPoint: tapPoint)
                }
            }
            catch {
                // just ignore
            }
        }
    }
    
    /// Handle double tap gesture
    
    @objc fileprivate func doubleTapGesture(tap: UITapGestureRecognizer) {
        guard doubleTapCameraSwitch == true else {
            return
        }
        switchCamera()
    }
    
    @objc private func panGesture(pan: UIPanGestureRecognizer) {
        
        guard swipeToZoom == true && self.currentCamera == .rear else {
            //ignore pan
            return
        }
        let currentTranslation    = pan.translation(in: view).y
        let translationDifference = currentTranslation - previousPanTranslation
        
        do {
            let captureDevice = AVCaptureDevice.devices().first
            try captureDevice?.lockForConfiguration()
            
            let currentZoom = captureDevice?.videoZoomFactor ?? 0.0
            
            if swipeToZoomInverted == true {
                zoomScale = min(maxZoomScale, max(1.0, min(currentZoom - (translationDifference / 75),  captureDevice!.activeFormat.videoMaxZoomFactor)))
            } else {
                zoomScale = min(maxZoomScale, max(1.0, min(currentZoom + (translationDifference / 75),  captureDevice!.activeFormat.videoMaxZoomFactor)))
                
            }
            
            captureDevice?.videoZoomFactor = zoomScale
            
            // Call Delegate function with current zoom scale
            DispatchQueue.main.async {
                self.cameraDelegate?.swiftyCam(self, didChangeZoomLevel: self.zoomScale)
            }
            
            captureDevice?.unlockForConfiguration()
            
        } catch {
            print("[SwiftyCam]: Error locking configuration")
        }
        
        if pan.state == .ended || pan.state == .failed || pan.state == .cancelled {
            previousPanTranslation = 0.0
        } else {
            previousPanTranslation = currentTranslation
        }
    }
    
    /**
     Add pinch gesture recognizer and double tap gesture recognizer to currentView
     
     - Parameter view: View to add gesture recognzier
     
     */
    
    fileprivate func addGestureRecognizers() {
        pinchGesture = UIPinchGestureRecognizer(target: self, action: #selector(zoomGesture(pinch:)))
        pinchGesture.delegate = self
        previewLayer.addGestureRecognizer(pinchGesture)
        
        let singleTapGesture = UITapGestureRecognizer(target: self, action: #selector(singleTapGesture(tap:)))
        singleTapGesture.numberOfTapsRequired = 1
        singleTapGesture.delegate = self
        previewLayer.addGestureRecognizer(singleTapGesture)
        
        let doubleTapGesture = UITapGestureRecognizer(target: self, action: #selector(doubleTapGesture(tap:)))
        doubleTapGesture.numberOfTapsRequired = 2
        doubleTapGesture.delegate = self
        previewLayer.addGestureRecognizer(doubleTapGesture)
        
        panGesture = UIPanGestureRecognizer(target: self, action: #selector(panGesture(pan:)))
        panGesture.delegate = self
        previewLayer.addGestureRecognizer(panGesture)
    }
}


// MARK: UIGestureRecognizerDelegate

extension SwiftyCamViewController : UIGestureRecognizerDelegate {
    
    /// Set beginZoomScale when pinch begins
    
    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer.isKind(of: UIPinchGestureRecognizer.self) {
            beginZoomScale = zoomScale;
        }
        return true
    }
}

extension SwiftyCamViewController: AVCaptureVideoDataOutputSampleBufferDelegate {
    
    /// 这是 AVCaptureVideoDataOutput sample buffer 的回调，获取帧
    public func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            print("无法获取图像缓冲区")
            return
        }
        
        let ciImage = CIImage(cvPixelBuffer: imageBuffer)
        let context = CIContext()
        if let cgImage = context.createCGImage(ciImage, from: ciImage.extent) {
            let uiImage = UIImage(cgImage: cgImage)
            DispatchQueue.main.async {
                self.processCapturedImage(image: uiImage)
            }
        }
    }
    
    func processCapturedImage(image: UIImage) {

        let ori = orientation.getVideoOrientation()

        switch (ori) {
        case .portrait:
                if SwiftyCamViewController.externalDevice {
                    capturedFrameImage = image.rotated(byDegrees: -90)
                } else {
                    capturedFrameImage = image.rotated(byDegrees: 90)
                }
            break

        case .portraitUpsideDown:
            capturedFrameImage = image.rotated(byDegrees: -90)
            break

        case .landscapeRight:
            // 判断是否在使用前摄
            if currentCamera == .rear {
                capturedFrameImage = image.rotated(byDegrees: 0)
            } else {
                capturedFrameImage = image.rotated(byDegrees: 180)
            }
            break
            
        case .landscapeLeft:
            // 判断是否在使用前摄
            if currentCamera == .rear {
                capturedFrameImage = image.rotated(byDegrees: -180)
            } else {
                capturedFrameImage = image.rotated(byDegrees: 0)
            }
            break
                        
        default:
            capturedFrameImage = image.rotated(byDegrees: 0)
            break
        }

        var hidePreview = false
        if let retrievedDictionary = UserDefaults.standard.dictionary(forKey: "MBCustomASRSettingViewController.userDefaultsKey") as? [String: String] {
            if let hide = retrievedDictionary["hide_preview"] {
                if hide == "true" {
                    hidePreview = true
                }
            }
        }
        
        if !hidePreview,
           captureFrameImageView == nil {
            captureFrameImageView = UIImageView()
            captureFrameImageView?.contentMode = .scaleAspectFit
            if let v = captureFrameImageView {
                self.view.addSubview(v)
                v.snp.makeConstraints({ make in
                    make.top.equalTo(MBConstants.shared.kNavBarHeight - 20)
                    make.left.equalTo(0)
                    make.width.equalTo(self.view.frame.width*0.12)
                    make.height.equalTo(self.view.frame.height*0.12)
                })
            }
        }
        
    }

}
