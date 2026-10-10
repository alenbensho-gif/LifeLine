LifeLine Smart Blood Bank Management System

Requirements: Java 17+ and a web browser.

Run:
  javac LifeLineServer.java
  java LifeLineServer
Then open http://localhost:8091

Demo admin:
  Email: admin@lifeline.com
  Password: admin123   (change it: set the LIFELINE_ADMIN_PASSWORD environment variable before the first run)

Pages:
- Home, Find Blood, Donor Centre, Emergency, Food Guide, Dashboard

Features:
- Registration and sign-in (passwords are salted and hashed; sign-in returns a session token)
- Donor profile: blood group and availability
- Blood search by group and location
- Emergency request form (signed-in users only; demo requests, nothing is sent)
- Admin: update blood stock, clear demo data
- Dashboard: stock table, stats, refill estimate (target 20 units per group, 30 for O+/O-)
- Request details (hospital contacts) are visible to admin and hospital accounts only
- In-app notification centre
- Food Guide: general food and fluid guidance by amount of blood lost (replaces the old Recovery Plate Builder)
- Data is saved in lifeline-data.ser (written safely via a temp file). If an old or unreadable file is found it is
  kept as lifeline-data.ser.bak and demo data is loaded.

What was fixed in this version:
- Server-side sessions: role and email are taken from the session, never from the request (stock, clear, donor-profile, emergency)
- Hashed passwords, no wildcard CORS, no donor list in public responses
- Server-side validation (blood group list, unit ranges, lengths, email format, request size); bad input returns 400
- Donors are matched by email and the availability flag is stored and counted
- Home "Find blood" button works; text now says data is saved; notification IDs are unique

Important:
- This is a learning prototype, not a production blood-bank system. For real use you would also need HTTPS, login rate-limiting, a real database, and a security review.
- Sessions are kept in memory, so everyone is signed out when the server restarts.
- No SMS, email, push notifications or real donor alerts are sent.
- The Food Guide is general educational information, not medical advice. Significant bleeding or symptoms such as faintness,
  breathlessness, chest pain or severe weakness need urgent medical attention.
- Do not use sample inventory as real-time availability; confirm with a registered blood bank.

If port 8091 is in use, change PORT in LifeLineServer.java.
