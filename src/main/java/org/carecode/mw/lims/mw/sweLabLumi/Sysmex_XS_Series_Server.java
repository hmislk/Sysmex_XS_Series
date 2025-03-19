package org.carecode.mw.lims.mw.sweLabLumi;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.DataBundle;
import org.carecode.lims.libraries.OrderRecord;
import org.carecode.lims.libraries.PatientRecord;
import org.carecode.lims.libraries.QueryRecord;
import org.carecode.lims.libraries.ResultsRecord;

public class Sysmex_XS_Series_Server {

    private static final Logger logger = LogManager.getLogger(Sysmex_XS_Series_Server.class);

    private static final char ENQ = 0x05;
    private static final char ACK = 0x06;
    private static final char STX = 0x02;
    private static final char ETX = 0x03;
    private static final char EOT = 0x04;
    private static final char CR = 0x0D;  // Carriage Return
    private static final char LF = 0x0A;  // Line Feed
    private static final char NAK = 0x15;
    private static final char NAN = 0x00; // Line Feed

    static String fieldD = "|";
    static String repeatD = Character.toString((char) 92);
    static String componentD = "^";
    static String escapeD = "&";

    boolean receivingQuery;
    boolean receivingResults;
    boolean respondingQuery;
    boolean respondingResults;
    boolean testing;
    boolean needToSendHeaderRecordForQuery;
    boolean needToSendPatientRecordForQuery;
    boolean needToSendOrderingRecordForQuery;
    boolean needToSendEotForRecordForQuery;

    private DataBundle patientDataBundle = new DataBundle();

    String patientId;
    static String sampleId;
    List<String> testNames;
    int frameNumber;
    char terminationCode = 'N';
    PatientRecord patientRecord;
    ResultsRecord resultRecord;
    OrderRecord orderRecord;
    QueryRecord queryRecord;

    private ServerSocket serverSocket;

    public void start(int port) {
        try {
            serverSocket = new ServerSocket(port);
            logger.info("Server started on port " + port);
            while (true) {
                try (Socket clientSocket = serverSocket.accept()) {
                    logger.info("New client connected: " + clientSocket.getInetAddress().getHostAddress());
                    handleClient(clientSocket);
                } catch (IOException e) {
                    logger.error("Error handling client connection", e);
                }
            }
        } catch (IOException e) {
            logger.error("Error starting server on port " + port, e);
        } finally {
            stop();
        }
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                logger.info("Server stopped.");
            }
        } catch (IOException e) {
            logger.error("Error stopping server", e);
        }
    }

    private void handleClient(Socket clientSocket) {
        LISCommunicator lisCommunicator = new LISCommunicator();

        try (InputStream in = new BufferedInputStream(clientSocket.getInputStream()); OutputStream out = new BufferedOutputStream(clientSocket.getOutputStream())) {

            StringBuilder astmMessage = new StringBuilder();
            boolean sessionActive = true;
            String sampleId = "";
            DataBundle dataBundle = new DataBundle();

            while (sessionActive) {
                int data = in.read();
                if (data == -1) {
                    break;  // End of stream
                }

                switch (data) {
                    case ENQ:
//                        logger.debug("Received ENQ");
                        out.write(ACK);
                        out.flush();
//                        logger.debug("Sent ACK");
                        break;
                    case ACK:
//                        logger.debug("ACK Received.");
                        handleAck(clientSocket, out);
                        break;
                    case EOT:
//                        logger.debug("EOT Received");
                        handleEot(out);
                        sessionActive = false;
                        break;
                    case STX:
                        astmMessage.setLength(0); // Start of new ASTM frame
                        break;
                    case ETX:
                        // End of ASTM message, process it
                        String message = astmMessage.toString().trim();
//                        logger.debug("Complete ASTM Message: " + message);

                        String[] lines = message.split("\n");

                        for (String line : lines) {
//                            logger.info("Received data: " + line);
                            processAstmLine(line, dataBundle);
                        }

                        astmMessage.setLength(0);  // Clear buffer after processing
                        out.write(ACK);  // Send acknowledgment
                        out.flush();
//                        logger.debug("Sent ACK after processing ASTM message");
                        break;
                    default:
                        astmMessage.append((char) data);
                        break;
                }
            }
        } catch (IOException e) {
            logger.error("Error during client communication", e);
        }
    }

    private void processAstmLine(String line, DataBundle dataBundle) {
        if (line == null || line.isEmpty()) {
            return;
        }

        int firstPipeIndex = line.indexOf('|');
        if (firstPipeIndex < 0) {
            logger.warn("No '|' found, skipping line: " + line);
            return;
        }

        String frameRecordPart = line.substring(0, firstPipeIndex);
        String remainder = line.substring(firstPipeIndex + 1);

        if (frameRecordPart.isEmpty()) {
            logger.warn("Empty frame/record part, skipping line: " + line);
            return;
        }

        char recordTypeChar = frameRecordPart.charAt(frameRecordPart.length() - 1);
        String frameNumberString = frameRecordPart.substring(0, frameRecordPart.length() - 1);

        logger.debug("Frame Number: " + frameNumberString
                + " | Record Type: " + recordTypeChar
                + " | Remainder: " + remainder);

        switch (recordTypeChar) {
            case 'H':
                logger.info("Header Record Detected.");
                // processHeader(remainder);
                break;
            case 'P':
                logger.info("Patient Record Detected.");
                patientRecord = parsePatientRecord(remainder);
                dataBundle.setPatientRecord(patientRecord);
                break;
            case 'O':
                logger.info("Order Record Detected.");
                orderRecord = parseOrderRecord(remainder);
                dataBundle.getOrderRecords().add(orderRecord);
                break;
            case 'R':
                logger.info("Result Record Detected.");
                ResultsRecord resultsRecord = parseResultsRecord(remainder);
                dataBundle.getResultsRecords().add(resultsRecord);
//                LISCommunicator.pushResults(dataBundle);
                break;
            case 'L':
                logger.info("Termination Record Detected.");
                LISCommunicator.pushResults(dataBundle);
                break;
            default:
                logger.warn("Unknown record type: " + recordTypeChar
                        + " | Remainder: " + remainder);
        }
    }

    public static ResultsRecord parseResultsRecord(String resultSegment) {
        System.out.println("DEBUG: Entering parseResultsRecord");
        System.out.println("DEBUG: resultSegment = " + resultSegment);

        if (resultSegment == null || resultSegment.isEmpty()) {
            logger.error("Result segment is null or empty.");
            System.out.println("ERROR: Result segment is null or empty.");
            return null;
        }

        String[] fields = resultSegment.split("\\|");
        System.out.println("DEBUG: Number of fields after split = " + fields.length);
        for (int i = 0; i < fields.length; i++) {
            System.out.println("DEBUG: Field[" + i + "] = " + fields[i]);
        }

        if (fields.length < 6) {
            logger.error("Insufficient fields in ASTM result segment: {}", resultSegment);
            System.out.println("ERROR: Insufficient fields in ASTM result segment.");
            return null;
        }

        // Extract frame number from field[0]
        int frameNumber;
        try {
            frameNumber = Integer.parseInt(fields[0].replaceAll("[^0-9]", ""));
            System.out.println("DEBUG: Frame number parsed: " + frameNumber);
        } catch (NumberFormatException e) {
            frameNumber = new Random().nextInt(1000);
            logger.warn("Failed to parse frame number, assigning random: {}", frameNumber);
            System.out.println("WARNING: Failed to parse frame number, assigned random: " + frameNumber);
        }
        logger.debug("Frame number extracted: {}", frameNumber);

        // Extract test code from field[1]
        String testCode = "UnknownTest";
        if (fields.length > 1 && fields[1] != null && !fields[1].isEmpty()) {
            System.out.println("DEBUG: Raw test code field (from field[1]) = " + fields[1]);
            String[] testDetails = fields[1].split("\\^");
            System.out.println("DEBUG: Number of parts in testDetails = " + testDetails.length);
            for (int i = 0; i < testDetails.length; i++) {
                System.out.println("DEBUG: testDetails[" + i + "] = " + testDetails[i]);
            }
            if (testDetails.length >= 5) {
                testCode = testDetails[4];  // Extract the test name from index 4 (e.g., "WBC")
                System.out.println("DEBUG: Test code extracted from testDetails[4]: " + testCode);
            } else {
                System.out.println("WARNING: Not enough parts in testDetails to extract test code.");
            }
        }
        logger.debug("Test code extracted: {}", testCode);

        // Extract result value from field[2]
        String resultValueString = fields.length > 2 ? fields[2] : "";
        // Extract result units from field[3]
        String resultUnits = fields.length > 3 ? fields[3] : "";
        // Extract result date/time from field[11] (if present)
        String resultDateTime = fields.length > 11 ? fields[11] : "";

        System.out.println("DEBUG: Extracted frameNumber = " + frameNumber);
        System.out.println("DEBUG: Extracted testCode = " + testCode);
        System.out.println("DEBUG: Extracted resultValueString (numeric result) = " + resultValueString);
        System.out.println("DEBUG: Extracted resultUnits = " + resultUnits);
        System.out.println("DEBUG: Extracted resultDateTime = " + resultDateTime);
        System.out.println("DEBUG: Using sampleId (class variable) = " + sampleId);

        ResultsRecord record = new ResultsRecord(
                frameNumber,
                testCode,
                resultValueString, // This is the numeric result value as a string (e.g., "5.56")
                resultUnits, // This is the unit string (e.g., "10*3/uL")
                resultDateTime,
                "Sysmex_XS_Series",
                sampleId
        );
        System.out.println("DEBUG: Created ResultsRecord: " + record);
        System.out.println("DEBUG: Exiting parseResultsRecord");

        return record;
    }

    public static OrderRecord parseOrderRecord(String orderSegment) {
        System.out.println("DEBUG: Entering parseOrderRecord");
        System.out.println("DEBUG: orderSegment = " + orderSegment);

        String[] fields = orderSegment.split("\\|");
        System.out.println("DEBUG: Number of fields after split = " + fields.length);
        for (int i = 0; i < fields.length; i++) {
            System.out.println("DEBUG: Field[" + i + "] = " + fields[i]);
        }

        // Extract frame number from fields[0]
        int frameNumber = 0;
        try {
            frameNumber = Integer.parseInt(fields[0].replaceAll("[^0-9]", ""));
            System.out.println("DEBUG: Frame number extracted: " + frameNumber);
        } catch (NumberFormatException e) {
            System.out.println("ERROR: Failed to parse frame number, assigning 0.");
        }

        // Extract sample ID from fields[2] (if present)
        sampleId = "UnknownSample";
        if (fields.length > 2 && fields[2] != null && !fields[2].isEmpty()) {
            String[] sampleDetails = fields[2].split("\\^");
            System.out.println("DEBUG: Number of parts in sampleDetails = " + sampleDetails.length);
            for (int i = 0; i < sampleDetails.length; i++) {
                System.out.println("DEBUG: sampleDetails[" + i + "] = " + sampleDetails[i]);
            }
            if (sampleDetails.length >= 3) {
                sampleId = sampleDetails[2].trim(); // Trim spaces
                System.out.println("DEBUG: Extracted sampleId = '" + sampleId + "'");
            } else {
                System.out.println("WARNING: Sample ID extraction failed, defaulting to 'UnknownSample'.");
            }
        }

        // Extract test names from fields[3] (if present)
        List<String> testNames = new ArrayList<>();
        if (fields.length > 3 && fields[3] != null && !fields[3].isEmpty()) {
            testNames = Arrays.stream(fields[3].split("\\^"))
                    .filter(s -> !s.isEmpty())
                    .map(String::trim)
                    .collect(Collectors.toList());
            System.out.println("DEBUG: Extracted test names: " + testNames);
        }

        System.out.println("DEBUG: Exiting parseOrderRecord");

        return new OrderRecord(
                frameNumber,
                sampleId,
                testNames,
                "", // No specimen code
                "", // No order date/time
                "" // No test information
        );
    }

    private void handleAck(Socket clientSocket, OutputStream out) throws IOException {
        // System.out.println("handleAck = ");
        // System.out.println("needToSendHeaderRecordForQuery = " + needToSendHeaderRecordForQuery);
        if (needToSendHeaderRecordForQuery) {
            logger.debug("Sending Header");
            String hm = createLimsHeaderRecord();
            sendResponse(hm, clientSocket);
            frameNumber = 2;
            needToSendHeaderRecordForQuery = false;
            needToSendPatientRecordForQuery = true;
        } else if (needToSendPatientRecordForQuery) {
            logger.debug("Creating Patient record ");
            patientRecord = getPatientDataBundle().getPatientRecord();
            if (patientRecord.getPatientName() == null) {
                patientRecord.setPatientName("Buddhika");
            }
            patientRecord.setFrameNumber(frameNumber);
            String pm = createLimsPatientRecord(patientRecord);
            sendResponse(pm, clientSocket);
            frameNumber = 3;
            needToSendPatientRecordForQuery = false;
            needToSendOrderingRecordForQuery = true;
        } else if (needToSendOrderingRecordForQuery) {
            logger.debug("Creating Order record ");
            if (testNames == null || testNames.isEmpty()) {
                testNames = Arrays.asList("Gluc GP");
            }
            orderRecord = getPatientDataBundle().getOrderRecords().get(0);
            orderRecord.setFrameNumber(frameNumber);
            String om = createLimsOrderRecord(orderRecord);
            sendResponse(om, clientSocket);
            frameNumber = 4;
            needToSendOrderingRecordForQuery = false;
            needToSendEotForRecordForQuery = true;
        } else if (needToSendEotForRecordForQuery) {
            // System.out.println("Creating an End record = ");
            String tmq = createLimsTerminationRecord(frameNumber, terminationCode);
            sendResponse(tmq, clientSocket);
            needToSendEotForRecordForQuery = false;
            receivingQuery = false;
            receivingResults = false;
            respondingQuery = false;
            respondingResults = false;
        } else {
            out.write(EOT);
            out.flush();
            logger.debug("Sent EOT");
        }
    }

    private void sendResponse(String response, Socket clientSocket) {
        String astmMessage = buildASTMMessage(response);
        try {
            OutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());
            out.write(astmMessage.getBytes());
            out.flush();
            logger.debug("Response sent: " + response);
        } catch (IOException e) {
            logger.error("Failed to send response", e);
        }
    }

    private void handleEot(OutputStream out) throws IOException {
        logger.debug("Handling eot");
        logger.debug(respondingQuery);
        if (respondingQuery) {
            patientDataBundle = LISCommunicator.pullTestOrdersForSampleRequests(patientDataBundle.getQueryRecords().get(0));
            logger.debug("Starting Transmission to send test requests");
            out.write(ENQ);
            out.flush();
            logger.debug("Sent ENQ");
        } else if (respondingResults) {
            LISCommunicator.pushResults(patientDataBundle);
        } else {
            logger.debug("Received EOT, ending session");
        }
    }

    public static String calculateChecksum(String frame) {
        String checksum = "00";
        int sumOfChars = 0;
        boolean complete = false;

        for (int idx = 0; idx < frame.length(); idx++) {
            int byteVal = frame.charAt(idx);

            switch (byteVal) {
                case 0x02: // STX
                    sumOfChars = 0;
                    break;
                case 0x03: // ETX
                case 0x17: // ETB
                    sumOfChars += byteVal;
                    complete = true;
                    break;
                default:
                    sumOfChars += byteVal;
                    break;
            }

            if (complete) {
                break;
            }
        }

        if (sumOfChars > 0) {
            checksum = Integer.toHexString(sumOfChars % 256).toUpperCase();
            return (checksum.length() == 1 ? "0" + checksum : checksum);
        }

        return checksum;
    }

    public String createHeaderMessage() {
        String headerContent = "1H|^&|||1^CareCode^1.0|||||||P|";
        return headerContent;
    }

    public String buildASTMMessage(String content) {
        String msdWithStartAndEnd = STX + content + CR + ETX;
        String checksum = calculateChecksum(msdWithStartAndEnd);
        String completeMsg = msdWithStartAndEnd + checksum + CR + LF;
        return completeMsg;
    }

    public String createLimsPatientRecord(PatientRecord patient) {
        // Delimiter used in the ASTM protocol
        String delimiter = "|";

        // Construct the start of the patient record, including frame number
        String patientStart = patient.getFrameNumber() + "P" + delimiter;

        // Concatenate patient information fields with actual patient data
        String patientInfo = "1" + delimiter
                + // Sequence Number
                patient.getPatientId() + delimiter
                + // Patient ID
                delimiter
                + // [Empty field for additional ID]
                delimiter
                + // [Empty field for more data]
                patient.getPatientName() + delimiter
                + // Patient Name
                delimiter
                + // [Empty field for more patient data]
                "U" + delimiter
                + // Sex (assuming 'U' for unspecified)
                delimiter
                + // [Empty field]
                delimiter
                + // [More empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [And more...]
                delimiter
                + // [And more...]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                delimiter
                + // [Continued empty fields]
                patient.getAttendingDoctor() + delimiter;    // Attending Doctor

        // Construct the full patient record
        return patientStart + patientInfo;
    }

    public static String createLimsOrderRecord(int frameNumber, String sampleId, List<String> testNames, String specimenCode, Date collectionDate, String testInformation) {
        // Delimiter used in the ASTM protocol

        String delimiter = "|";

        // SimpleDateFormat to format the Date object to the required ASTM format
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        String formattedDate = dateFormat.format(collectionDate);

        // Construct each field individually
        String frameNumberAndRecordType = frameNumber + "O"; // Combining frame number and Record Type 'O' without a delimiter
        String sequenceNumber = "1";
        String sampleID = sampleId;
        String instrumentSpecimenID = ""; // Instrument Specimen ID (Blank)
        StringBuilder orderedTests = new StringBuilder();
        for (int i = 0; i < testNames.size(); i++) {
            orderedTests.append("^^^").append(testNames.get(i));
            if (i < testNames.size() - 1) {
                orderedTests.append("\\"); // Append backslash except after the last test name
            }
        }
        String specimenType = specimenCode;
        String fillField = ""; // Fill field (Blank)
        String dateTimeOfCollection = formattedDate;
        String priority = ""; // Priority (Blank)

        String physicianID = testInformation;
        String physicianName = ""; // Physician Name (Blank)
        String userFieldNo1 = ""; // User Field No. 1 (Blank)
        String userFieldNo2 = ""; // User Field No. 2 (Blank)
        String labFieldNo1 = ""; // Laboratory Field No. 1 (Blank)
        String labFieldNo2 = ""; // Laboratory Field No. 2 (Blank)
        String dateTimeSpecimenReceived = ""; // Date/Time specimen received in lab (Blank)
        String specimenDescriptor = ""; // Specimen descriptor (Blank)
        String orderingMD = ""; // Ordering MD (Blank)
        String locationDescription = ""; // Location description (Blank)
        String ward = ""; // Ward (Blank)
        String invoiceNumber = ""; // Invoice Number (Blank)
        String reportType = ""; // Report type (Blank)
        String reservedField1 = ""; // Reserved Field (Blank)
        String reservedField2 = ""; // Reserved Field (Blank)
        String transportInformation = ""; // Transport information (Blank)

        // Concatenate all fields with delimiters
        return frameNumberAndRecordType + delimiter
                + sequenceNumber + delimiter
                + sampleID + delimiter
                + instrumentSpecimenID + delimiter
                + orderedTests + delimiter;
//                + specimenType + delimiter
//                + fillField + delimiter
//                + dateTimeOfCollection + delimiter
//                + priority + delimiter
//                
//                + physicianID + delimiter
//                + physicianName + delimiter
//                + userFieldNo1 + delimiter
//                + userFieldNo2 + delimiter
//                + labFieldNo1 + delimiter
//                + labFieldNo2 + delimiter
//                + dateTimeSpecimenReceived + delimiter
//                + specimenDescriptor + delimiter
//                + orderingMD + delimiter
//                + locationDescription + delimiter
//                + ward + delimiter
//                + invoiceNumber + delimiter
//                + reportType + delimiter
//                + reservedField1 + delimiter
//                + reservedField2 + delimiter
//                + transportInformation;
    }

    public String createLimsHeaderRecord() {
        String analyzerNumber = "1";
        String analyzerName = "LIS host";
        String databaseVersion = "1.0";
        String hr1 = "1H";
        String hr2 = fieldD + repeatD + componentD + escapeD;
        String hr3 = "";
        String hr4 = "";
        String hr5 = analyzerNumber + componentD + analyzerName + componentD + databaseVersion;
        String hr6 = "";
        String hr7 = "";
        String hr8 = "";
        String hr9 = "";
        String hr10 = "";
        String hr11 = "";
        String hr12 = "P";
        String hr13 = "";
        String hr14 = "20240508221500";
        String header = hr1 + hr2 + fieldD + hr3 + fieldD + hr4 + fieldD + hr5 + fieldD + hr6 + fieldD + hr7 + fieldD + hr8 + fieldD + hr9 + fieldD + hr10 + fieldD + hr11 + fieldD + hr12 + fieldD + hr13 + fieldD + hr14;

        header = hr1 + hr2 + fieldD + hr3 + fieldD + hr4 + fieldD + hr5 + fieldD + hr6 + fieldD + hr7 + fieldD + hr8 + fieldD + hr9 + fieldD + hr10 + fieldD + hr11 + fieldD + hr12;

        return header;

    }

    public String createLimsOrderRecord(OrderRecord order) {
        return createLimsOrderRecord(order.getFrameNumber(), order.getSampleId(), order.getTestNames(), order.getSpecimenCode(), order.getOrderDateTime(), order.getTestInformation());
    }

    public String createLimsTerminationRecord(int frameNumber, char terminationCode) {
        String delimiter = "|";
        String terminationStart = frameNumber + "L" + delimiter;
        String terminationInfo = "1" + delimiter + terminationCode; // '1' is the record number, usually fixed
        return terminationStart + terminationInfo;
    }

    public static PatientRecord parsePatientRecord(String patientSegment) {
        System.out.println("parsePatientRecord");
        System.out.println("patientSegment = " + patientSegment);
        String[] fields = patientSegment.split("\\|");

        int frameNumber = fields.length > 0 ? Integer.parseInt(fields[0].replaceAll("[^0-9]", "")) : 0;
        String patientId = fields.length > 1 ? fields[1] : "";
        String additionalId = fields.length > 4 ? fields[3] : ""; // Ensure index 3 exists
        String patientName = fields.length > 5 ? fields[4] : "";
        String patientSecondName = fields.length > 7 ? fields[6] : "";
        String patientSex = fields.length > 8 ? fields[7] : "";
        String race = ""; // Not available
        String dob = ""; // Not available
        String patientAddress = fields.length > 12 ? fields[11] : "";
        String patientPhoneNumber = fields.length > 15 ? fields[14] : "";
        String attendingDoctor = fields.length > 16 ? fields[15] : "";

        return new PatientRecord(
                frameNumber,
                patientId,
                additionalId,
                patientName,
                patientSecondName,
                patientSex,
                race,
                dob,
                patientAddress,
                patientPhoneNumber,
                attendingDoctor
        );
    }

    public static String extractSampleIdFromQueryRecord(String astm2Message) {
        // Step 1: Discard everything before "Q|"
        int startIndex = astm2Message.indexOf("Q|");
        if (startIndex == -1) {
            return null; // "Q|" not found in the message
        }
        String postQ = astm2Message.substring(startIndex);

        // Step 2: Get the string between the second and third "|"
        String[] fields = postQ.split("\\|");
        if (fields.length < 3) {
            return null; // Not enough fields
        }
        String secondField = fields[2]; // Get the field after the second "|"

        // Step 3: Get the string between the first and second "^"
        String[] sampleDetails = secondField.split("\\^");
        if (sampleDetails.length < 2) {
            return null; // Not enough data within the field
        }
        return sampleDetails[1]; // This should be the sample ID
    }

    public static String extractSampleIdFromOrderRecord(String astm2Message) {
        // Split the message by the '|' delimiter
        String[] fields = astm2Message.split("\\|");

        // Assuming the sample ID is in the third field (index 2)
        if (fields.length > 2) {
            // Extract the sample ID field (third field)
            String tmpSampleId = fields[2];

            // Split the tmpSampleId by '^' and return the first part
            String[] sampleIdParts = tmpSampleId.split("\\^");
            return sampleIdParts[0]; // Return only the part before the first '^'
        } else {
            return null; // or throw an exception if you prefer
        }
    }

    public static QueryRecord parseQueryRecord(String querySegment) {
        // System.out.println("querySegment = " + querySegment);
        String tmpSampleId = extractSampleIdFromQueryRecord(querySegment);
        // System.out.println("tmpSampleId = " + tmpSampleId);
        sampleId = tmpSampleId;
        // System.out.println("Sample ID: " + tmpSampleId); // Debugging
        return new QueryRecord(
                0,
                tmpSampleId,
                "",
                ""
        );
    }

    public DataBundle getPatientDataBundle() {
        if (patientDataBundle == null) {
            patientDataBundle = new DataBundle();
        }
        return patientDataBundle;
    }

    public void setPatientDataBundle(DataBundle patientDataBundle) {
        this.patientDataBundle = patientDataBundle;
    }

}
