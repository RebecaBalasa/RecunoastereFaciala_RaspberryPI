#!/usr/bin/python3

from gpiozero import LED, MotionSensor, Buzzer
from picamera2 import Picamera2
from imutils.video import FPS
import face_recognition
import firebase_admin
from firebase_admin import credentials, db, messaging, storage
import imutils
import pickle
import time
import cv2
import threading
import sys
import io
import uuid
import datetime

# GPIO
red_led   = LED(17)
green_led = LED(16)
buzzer    = Buzzer(26)
pir       = MotionSensor(4)

# FIREBASE 
DB_URL         = 'https://facerecognition-41bc6-default-rtdb.europe-west1.firebasedatabase.app/'
STORAGE_BUCKET = 'facerecognition-41bc6.firebasestorage.app'

try:
    cred = credentials.Certificate('serviceAccountKey.json')
    firebase_admin.initialize_app(cred, {
        'databaseURL': DB_URL,
        'storageBucket': STORAGE_BUCKET
    })
    print("[INFO] Conectat cu succes la Firebase!")
except Exception as e:
    print(f"[EROARE] Initializare Firebase: {e}")
    sys.exit(1)


# STORAGE
def uploadeaza_poza(frame, folder: str = "detectii"):
    """
    Uploadeaza frame-ul ca JPEG in Firebase Storage.
    Returneaza (cale_relativa, http_url_descarcabil) sau (None, None) la eroare.
    cale_relativa -> salvata in DB, folosita de Android SDK
    http_url -> trimis in FCM pentru afisarea pozei in notificare
    """
    try:
        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        filename = f"{folder}/{timestamp}.jpg"

        success, buffer = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if not success:
            print("[EROARE] Nu s-a putut encoda imaginea.")
            return None, None

        byte_stream = io.BytesIO(buffer.tobytes())

        bucket = storage.bucket()
        blob = bucket.blob(filename)
        blob.upload_from_file(byte_stream, content_type='image/jpeg')

        download_token = str(uuid.uuid4())
        blob.metadata = {"firebaseStorageDownloadTokens": download_token}
        blob.patch()

        encoded_name = filename.replace("/", "%2F")
        http_url = (
            f"https://firebasestorage.googleapis.com/v0/b/{STORAGE_BUCKET}"
            f"/o/{encoded_name}?alt=media&token={download_token}"
        )

        print(f"[STORAGE] Poza salvata: {filename}")
        print(f"[STORAGE] Download URL: {http_url}")
        return filename, http_url

    except Exception as e:
        print(f"[EROARE] Upload Storage: {e}")
        return None, None


# FIREBASE ALERT
def trimite_alerta_firebase(status: str, frame=None, is_emergency: bool = False):
    try:
        photo_path = None
        http_url = None

        if frame is not None:
            folder = "intrus" if is_emergency else "acces_permis"
            photo_path, http_url = uploadeaza_poza(frame, folder=folder)

        entry = {
            'status': status,
            'timestamp': int(time.time() * 1000),
        }
        if photo_path:
            entry['image_url'] = photo_path

        db.reference('detections').push(entry)
        print(f"[FIREBASE] Salvat in DB: {status}")

        if is_emergency:
            notif_title = '!!! ALERTA INTRUS !!!'
            notif_body = status
            priority = 'high'
        else:
            notif_title = '✅ Acces Permis'
            notif_body = status
            priority = 'normal'

        message = messaging.Message(
            data={
                'title': notif_title,
                'body': notif_body,
                'timestamp': str(int(time.time() * 1000)),
                'image_url': photo_path or '',
                'http_url': http_url or '',
                'emergency': str(is_emergency).lower(),
            },
            notification=messaging.Notification(
                title=notif_title,
                body=notif_body,
            ),
            android=messaging.AndroidConfig(
                priority=priority,
                notification=messaging.AndroidNotification(
                    channel_id='face_detection_channel',
                    sound='default',
                    default_vibrate_timings=True,
                    default_light_settings=True,
                    image=http_url,
                ),
            ),
            topic='alerts',
        )
        response = messaging.send(message)
        print(f"[FIREBASE] Notificare trimisa! ID: {response}")

    except firebase_admin.exceptions.FirebaseError as e:
        print(f"[EROARE] Firebase: {e}")
    except Exception as e:
        print(f"[EROARE] Generala Firebase: {e}")


# ENCODINGS
encodingsP = "encodings.pickle"

print("[INFO] Se incarca encodings...")
with open(encodingsP, "rb") as f:
    data = pickle.loads(f.read())

# CAMERA 
picam2 = Picamera2()
picam2.configure(
    picam2.create_preview_configuration(
        main={"size": (640, 480), "format": "BGR888"}
    )
)

# STATE
currentname = "unknown"
stop_camera = threading.Event()
last_firebase_status = None
last_alert_time = 0
ALERT_COOLDOWN = 10

red_led.off()
green_led.off()
buzzer.off()


def alerta_daca_nou(status: str, frame=None, is_emergency: bool = False):
    global last_firebase_status, last_alert_time

    now = time.time()
    if status != last_firebase_status or (now - last_alert_time) >= ALERT_COOLDOWN:
        threading.Thread(
            target=trimite_alerta_firebase,
            args=(status, frame, is_emergency),
            daemon=True
        ).start()
        last_firebase_status = status
        last_alert_time = now


def wait_no_motion():
    pir.wait_for_no_motion()
    stop_camera.set()


print("[INFO] Se asteapta stabilizarea PIR (3s)...")
time.sleep(3)

while True:
    print("[INFO] Asteptare miscare...")
    pir.wait_for_motion()
    print("[INFO] Miscare detectata!")

    stop_camera.clear()
    picam2.start()
    time.sleep(1.0)

    fps = FPS().start()
    threading.Thread(target=wait_no_motion, daemon=True).start()

    while not stop_camera.is_set():
        frame = picam2.capture_array()
        frame = cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)
        frame = imutils.resize(frame, width=500)

        boxes = face_recognition.face_locations(frame)
        encodings = face_recognition.face_encodings(frame, boxes)
        names = []

        for encoding in encodings:
            matches = face_recognition.compare_faces(data["encodings"], encoding)
            name = "Unknown"

            if True in matches:
                matched_idxs = [i for i, b in enumerate(matches) if b]
                counts = {}

                for i in matched_idxs:
                    n = data["names"][i]
                    counts[n] = counts.get(n, 0) + 1

                name = max(counts, key=counts.get)

                if currentname != name:
                    currentname = name
                    print(f"[INFO] Fata recunoscuta: {name}")
                    alerta_daca_nou(
                        f"Acces permis: {name}",
                        frame=frame.copy(),
                        is_emergency=False
                    )

            names.append(name)

        if names:
            recognized = any(n != "Unknown" for n in names)
            if recognized:
                green_led.on()
                red_led.off()
                buzzer.off()
            else:
                red_led.on()
                green_led.off()
                buzzer.on()
                alerta_daca_nou(
                    "Persoana NEAUTORIZATA detectata!",
                    frame=frame.copy(),
                    is_emergency=True
                )
                time.sleep(12)
        else:
            red_led.off()
            green_led.off()
            buzzer.off()

        for (top, right, bottom, left), name in zip(boxes, names):
            color = (0, 255, 0) if name != "Unknown" else (0, 0, 255)
            cv2.rectangle(frame, (left, top), (right, bottom), color, 2)
            y = top - 15 if top - 15 > 15 else top + 15
            cv2.putText(frame, name, (left, y), cv2.FONT_HERSHEY_SIMPLEX, 0.8, color, 2)

        cv2.imshow("Facial Recognition is Running", frame)

        if cv2.waitKey(1) & 0xFF == ord("q"):
            fps.stop()
            red_led.off()
            green_led.off()
            buzzer.off()
            cv2.destroyAllWindows()
            picam2.stop()
            sys.exit(0)

        fps.update()

    print("[INFO] Miscare oprita.")
    fps.stop()
    buzzer.off()
    red_led.off()
    green_led.off()
    cv2.destroyAllWindows()
    picam2.stop()

    currentname = "unknown"
    last_firebase_status = None
