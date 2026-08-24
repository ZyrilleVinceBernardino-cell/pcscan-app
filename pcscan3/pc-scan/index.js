const { onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { logger } = require("firebase-functions");
const sgMail = require("@sendgrid/mail");

// Set your SendGrid API key (HARDCODED KEYS REMOVED FOR GITHUB SECRET SCANNING — set via env var at deploy time!)
sgMail.setApiKey(process.env.SENDGRID_API_KEY || "");

exports.sendOtpCodeToEmail = onDocumentUpdated({
  document: "users/{userId}",
  region: "asia-east2"
}, async (event) => {
  try {
    const snapshot = event.data;
    if (!snapshot) {
      logger.error("No data associated with the event");
      return null;
    }

    const newValue = snapshot.after.data();
    const previousValue = snapshot.before.data();

    logger.info("Document updated", { userId: event.params.userId, hasOtp: !!newValue.otpCode });

    // Only send email if OTP was just added/updated
    if (newValue.otpCode && newValue.otpCode !== (previousValue?.otpCode || "")) {
      logger.info("Sending OTP email", { to: newValue.email });
      
      const msg = {
        to: newValue.email,
        from: "lalisamanoban933@gmail.com", // Must be verified in SendGrid
        subject: "Your PC Scan OTP Code",
        text: `Your OTP code is: ${newValue.otpCode}`,
        html: `<strong>Your OTP code is: ${newValue.otpCode}</strong>`,
      };

      await sgMail.send(msg);
      logger.info("OTP email sent successfully");
    } else {
      logger.info("No new OTP to send");
    }
    return null;
  } catch (error) {
    logger.error("Error sending OTP email", { error: error.message, stack: error.stack });
    return null;
  }
});
