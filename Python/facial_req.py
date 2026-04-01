#!/usr/bin/python3

from gpiozero import LED, MotionSensor, Buzzer
from picamera2 import Picamera2
from imutils.video import FPS
import face_recognition
import firebase_admin
from firebase_admin import credentials, db, messaging
import imutils
import pickle
import time
import cv2
import threading
import sys

red_led = LED(17)
green_led = LED(16)
buzzer = Buzzer(26)
pir = MotionSensor(4)

DB_URL = 'https://facerecognition-41bc6-default-rtdb.europe-west1.firebasedatabase.app/'

try:
    cred = credentials.Certificate('serviceAccountKey.json')
    firebase_admin.initialize_app(cred, {'databaseURL': DB_URL})
    print("[INFO] Conectat cu succes la Firebase!")
except Exception as e:
    print(f"[EROARE] Initializare Firebase: {e}")
    sys.exit(1)


def trimite_alerta_firebase(status: str, is_emergency: bool = False):
    try:
        db.reference('detections').push({
            'status': status,
            'timestamp': int(time.time() * 1000)
        })
        print(f"[FIREBASE] Salvat in DB: {status}")

        if is_emergency:
            message = messaging.Message(
                data={
                    'title': '!!! ALERTA INTRUS !!!',
                    'body': status,
                    'timestamp': str(int(time.time() * 1000)),
                },
                notification=messaging.Notification(
                    title='!!! ALERTA INTRUS !!!',
                    body=status,
                ),
                android=messaging.AndroidConfig(
                    priority='high',
                    notification=messaging.AndroidNotification(
                        channel_id='face_detection_channel',
                        sound='default',
                        default_vibrate_timings=True,
                        default_light_settings=True,
                    ),
                ),
                topic='alerts',
            )

            response = messaging.send(message)
            print(f"[FIREBASE] Notificare trimisa! ID: {response}")

    except firebase_admin.exceptions.FirebaseError as e:
        print(f"[EROARE] Firebase FCM: {e}")
    except Exception as e:
        print(f"[EROARE] Generala Firebase: {e}")


encodingsP = "encodings.pickle"

print("[INFO] Se incarca encodings + face detector...")
with open(encodingsP, "rb") as f:
    data = pickle.loads(f.read())

picam2 = Picamera2()
picam2.configure(
    picam2.create_preview_configuration(
        main={"size": (640, 480), "format": "BGR888"}
    )
)

currentname = "unknown"
stop_camera = threading.Event()

last_firebase_status = None
last_alert_time = 0
ALERT_COOLDOWN = 10

red_led.off()
green_led.off()
buzzer.off()


def alerta_daca_nou(status: str, is_emergency: bool = False):
    global last_firebase_status, last_alert_time

    now = time.time()

    if status != last_firebase_status or (now - last_alert_time) >= ALERT_COOLDOWN:
        threading.Thread(
            target=trimite_alerta_firebase,
            args=(status, is_emergency),
            daemon=True
        ).start()

        last_firebase_status = status
        last_alert_time = now


def wait_no_motion():
    pir.wait_for_no_motion()
    stop_camera.set()


print("[INFO] Se asteapta stabilizarea PIR...")
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
                    alerta_daca_nou(f"Acces permis: {name}", is_emergency=False)

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
                alerta_daca_nou("Persoana NEAUTORIZATA detectata!", is_emergency=True)
                time.sleep(5)
        else:
            red_led.off()
            green_led.off()
            buzzer.off()

        for (top, right, bottom, left), name in zip(boxes, names):
            color = (0, 255, 0) if name != "Unknown" else (0, 0, 255)

            cv2.rectangle(frame, (left, top), (right, bottom), color, 2)

            y = top - 15 if top - 15 > 15 else top + 15
            cv2.putText(
                frame,
                name,
                (left, y),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.8,
                color,
                2
            )

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