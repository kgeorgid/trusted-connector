package de.fhg.ids.comm.ws.protocol.rat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

import de.fhg.aisec.ids.messages.AttestationProtos.ControllerToTpm;
import de.fhg.aisec.ids.messages.AttestationProtos.ControllerToTpm.Code;
import de.fhg.aisec.ids.messages.AttestationProtos.TpmToController;
import de.fhg.aisec.ids.messages.Idscp.AttestationLeave;
import de.fhg.aisec.ids.messages.Idscp.AttestationRequest;
import de.fhg.aisec.ids.messages.Idscp.AttestationResponse;
import de.fhg.aisec.ids.messages.Idscp.AttestationResult;
import de.fhg.aisec.ids.messages.Idscp.ConnectorMessage;
import de.fhg.aisec.ids.messages.Idscp.IdsAttestationType;
import de.fhg.aisec.ids.messages.Idscp.Pcr;
import de.fhg.ids.comm.unixsocket.UnixSocketThread;
import de.fhg.ids.comm.unixsocket.UnixSocketResponsHandler;
import de.fhg.ids.comm.ws.protocol.fsm.Event;
import de.fhg.ids.comm.ws.protocol.fsm.FSM;

public class RemoteAttestationClientHandler {
	private final FSM fsm;
	private String myNonce;
	private String yourNonce;
	private IdsAttestationType aType;
	private boolean attestationSucccessfull = false;
	private Logger LOG = LoggerFactory.getLogger(RemoteAttestationClientHandler.class);
	private UnixSocketResponsHandler handler;
	private UnixSocketThread client;
	private Thread thread;
	private ByteString yourQuoted;
	private ByteString yourSignature;
	private String certUri;
	
	public RemoteAttestationClientHandler(FSM fsm, IdsAttestationType type) {
		this.fsm = fsm;
		this.aType = type;
		try {
			this.client = new UnixSocketThread();
			this.thread = new Thread(client);
			this.thread.setDaemon(true);
			this.thread.start();
			this.handler = new UnixSocketResponsHandler();
		} catch (IOException e) {
			LOG.debug("could not initialze thread!");
			e.printStackTrace();
		}		
	}

	public MessageLite enterRatRequest(Event e) {
		this.myNonce = NonceGenerator.generate();
		return ConnectorMessage
				.newBuilder()
				.setId(0)
				.setType(ConnectorMessage.Type.RAT_REQUEST)
				.setAttestationRequest(
						AttestationRequest
						.newBuilder()
						.setAtype(this.aType)
						.setQualifyingData(this.myNonce)
						.build())
				.build();
	}

	public MessageLite sendTPM2Ddata(Event e) {
		this.yourNonce = e.getMessage().getAttestationResponse().getQualifyingData().toString();
		this.yourQuoted = e.getMessage().getAttestationResponse().getQuotedBytes();
		this.yourSignature = e.getMessage().getAttestationResponse().getSignatureBytes();
		this.certUri = e.getMessage().getAttestationResponse().getCertificateUri();
		LOG.debug("----------------------------------------------------->msg:" + e.getMessage().toString());
		byte[] publicKey = null;
		try {
			publicKey = this.fetchPublicKey(this.certUri);
		} catch (Exception ex) {
			LOG.debug("error: exception " + ex.getMessage());
			ex.printStackTrace();
		}
		
		StringBuilder sb = new StringBuilder();
	    for (byte b : publicKey) {
	        sb.append(String.format("%02X ", b));
	    }
		
		LOG.debug("client fetched public key: " + sb.toString());
		
		ControllerToTpm msg = ControllerToTpm
								.newBuilder()
								.setAtype(this.aType)
								.setQualifyingData(this.yourNonce)
								.setCode(Code.INTERNAL_ATTESTATION_REQ)
								.build();
		
		try {
			client.send(msg.toByteArray(), this.handler);
			TpmToController answer = this.handler.waitForResponse();
			return ConnectorMessage
					.newBuilder()
					.setId(0)
					.setType(ConnectorMessage.Type.RAT_RESPONSE)
					.setAttestationResponse(
							AttestationResponse
							.newBuilder()
							.setAtype(this.aType)
							.setHalg(answer.getHalg())
							.setQuoted(answer.getQuoted())
							.setSignature(answer.getSignature())
							.addAllPcrValues(answer.getPcrValuesList())
							.setCertificateUri(answer.getCertificateUri())
							.build()
							)
					.build();
		} catch (IOException e1) {
			LOG.debug("IOException when writing to unix socket");
			e1.printStackTrace();
			return ConnectorMessage
					.newBuilder()
					.build();
		} catch (InterruptedException e1) {
			LOG.debug("InterruptedException when writing to unix socket");
			e1.printStackTrace();
			return ConnectorMessage
					.newBuilder()
					.build();
		}
	}
	
	public MessageLite sendResult(Event e) {
		this.attestationSucccessfull = false;

				
		
		return ConnectorMessage
				.newBuilder()
				.setId(0)
				.setType(ConnectorMessage.Type.RAT_RESULT)
				.setAttestationResult(
						AttestationResult
						.newBuilder()
						.setAtype(this.aType)
						.setResult(this.attestationSucccessfull)
						.build()
						)
				.build();
	}
	
	private byte[] fetchPublicKey(String uri) throws Exception {
		LOG.debug("URL:"+uri);
		URL cert = new URL(uri);
		BufferedReader in = new BufferedReader(new InputStreamReader(cert.openStream()));
		String base64 = "";
		String inputLine = "";
        while ((inputLine = in.readLine()) != null) {
        	base64 += inputLine;
        }
        in.close();
        return javax.xml.bind.DatatypeConverter.parseBase64Binary(base64);
	}
	

	public MessageLite leaveRatRequest(Event e) {
		this.thread.interrupt();
		return ConnectorMessage
				.newBuilder()
				.setId(0)
				.setType(ConnectorMessage.Type.RAT_LEAVE)
				.setAttestationLeave(
						AttestationLeave
						.newBuilder()
						.setAtype(this.aType)
						.build()
						)
				.build();
	}	

}
