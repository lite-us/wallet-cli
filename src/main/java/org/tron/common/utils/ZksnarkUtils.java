/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.common.utils;


import static org.tron.protos.Protocol.Transaction.Contract.ContractType.ZksnarkV0TransferContract;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.api.ZkGrpcAPI.JSInputMsg;
import org.tron.api.ZkGrpcAPI.JSOutputMsg;
import org.tron.api.ZkGrpcAPI.SproutNoteMsg;
import org.tron.api.ZkGrpcAPI.Uint256Msg;
import org.tron.common.crypto.ECKey;
import org.tron.common.crypto.ECKey.ECDSASignature;
import org.tron.common.crypto.Sha256Hash;
import org.tron.common.crypto.blake2b.Blake2b;
import org.tron.common.crypto.eddsa.EdDSAEngine;
import org.tron.common.crypto.eddsa.EdDSAPublicKey;
import org.tron.common.crypto.eddsa.spec.EdDSANamedCurveSpec;
import org.tron.common.crypto.eddsa.spec.EdDSANamedCurveTable;
import org.tron.common.crypto.eddsa.spec.EdDSAPublicKeySpec;
import org.tron.common.zksnark.CmUtils.CmTuple;
import org.tron.common.zksnark.ShieldAddressGenerator;
import org.tron.protos.Contract.BN128G1;
import org.tron.protos.Contract.BN128G2;
import org.tron.protos.Contract.ZksnarkV0TransferContract;
import org.tron.protos.Contract.zkv0proof;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract;

public class ZksnarkUtils {

  public static byte[] computeHSig(ZksnarkV0TransferContract zkContract) {
    byte[] message = ByteUtil
        .merge(zkContract.getRandomSeed().toByteArray(), zkContract.getNf1().toByteArray(),
            zkContract.getNf2().toByteArray(), zkContract.getPksig().toByteArray());
    return Blake2b.hash(message);
  }

  public static byte[] computeZkSignInput(ZksnarkV0TransferContract zkContract) {
    byte[] hSig = computeHSig(zkContract);
    ZksnarkV0TransferContract.Builder builder = zkContract.toBuilder();
    builder.setRandomSeed(ByteString.EMPTY);
    builder.setPksig(ByteString.copyFrom(hSig));
    return builder.build().toByteArray();
  }

  public static EdDSAPublicKey byte2PublicKey(byte[] pk) {
    EdDSANamedCurveSpec curveSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
    EdDSAPublicKeySpec spec = new EdDSAPublicKeySpec(pk, curveSpec);
    EdDSAPublicKey publicKey = new EdDSAPublicKey(spec);
    return publicKey;
  }

  public static JSOutputMsg computeOutputMsg(byte[] to, long v, String memo) {
    JSOutputMsg.Builder output = JSOutputMsg.newBuilder();
    if (ArrayUtils.isEmpty(to) || to.length != 64) {
      to = new byte[64];
      new Random().nextBytes(to);
      v = 0;
    }

    output.setAPk(
        Uint256Msg.newBuilder().setHash(ByteString.copyFrom(Arrays.copyOfRange(to, 0, 32))));
    output.setPkEnc(
        Uint256Msg.newBuilder().setHash(ByteString.copyFrom(Arrays.copyOfRange(to, 32, 64))));
    output.setValue(v);

    if (StringUtils.isEmpty(memo)) {
      memo = "Default memo";
    }
    output.setMemo(ByteString.copyFromUtf8(memo));
    return output.build();
  }

  public static JSInputMsg CmTuple2JSInputMsg(CmTuple in) {
    JSInputMsg.Builder input = JSInputMsg.newBuilder();
    SproutNoteMsg.Builder note = SproutNoteMsg.newBuilder();
    //TODO witness
    byte[] ask;
    byte[] apk;
    long v;
    byte[] rho;
    byte[] r;

    org.tron.common.zksnark.ShieldAddressGenerator shieldAddressGenerator = new ShieldAddressGenerator();
    if (in == null) {
      ask = shieldAddressGenerator.generatePrivateKey();
      apk = shieldAddressGenerator.generatePublicKey(ask);
      v = 0;
      rho = new byte[32];
      new Random().nextBytes(rho);
      r = new byte[32];
      new Random().nextBytes(r);
    } else {
      ask = in.addr_sk;
      apk = in.addr_pk;
      v = ByteArray.toLong(in.v);
      rho = in.rho;
      r = in.r;
    }
    note.setValue(v);
    note.setAPk(Uint256Msg.newBuilder().setHash(ByteString.copyFrom(apk)));
    note.setRho(Uint256Msg.newBuilder().setHash(ByteString.copyFrom(rho)));
    note.setR(Uint256Msg.newBuilder().setHash(ByteString.copyFrom(r)));
    input.setKey(Uint256Msg.newBuilder().setHash(ByteString.copyFrom(ask)));
    input.setNote(note);
    return input.build();
  }

  public static zkv0proof byte2Proof(byte[] in) {
    if (ArrayUtils.isEmpty(in) || in.length != 576) {
      return null;
    }
    zkv0proof.Builder builder = zkv0proof.newBuilder();

    builder.setA(BN128G1.newBuilder().setX(ByteString.copyFrom(Arrays.copyOfRange(in, 0, 32)))
        .setY(ByteString.copyFrom(Arrays.copyOfRange(in, 32, 64))));
    builder.setAP(BN128G1.newBuilder().setX(ByteString.copyFrom(Arrays.copyOfRange(in, 64, 96)))
        .setY(ByteString.copyFrom(Arrays.copyOfRange(in, 96, 128))));
    builder.setB(BN128G2.newBuilder().setX1(ByteString.copyFrom(Arrays.copyOfRange(in, 128, 160)))
        .setX2(ByteString.copyFrom(Arrays.copyOfRange(in, 160, 192)))
        .setY1(ByteString.copyFrom(Arrays.copyOfRange(in, 192, 224)))
        .setY2(ByteString.copyFrom(Arrays.copyOfRange(in, 224, 256))));
    builder.setBP(BN128G1.newBuilder().setX(ByteString.copyFrom(Arrays.copyOfRange(in, 256, 288)))
        .setY(ByteString.copyFrom(Arrays.copyOfRange(in, 288, 320))));
    builder.setC(BN128G1.newBuilder().setX(ByteString.copyFrom(Arrays.copyOfRange(in, 320, 352)))
        .setY(ByteString.copyFrom(Arrays.copyOfRange(in, 352, 384))));
    builder.setCP(BN128G1.newBuilder().setX(ByteString.copyFrom(Arrays.copyOfRange(in, 384, 416)))
        .setY(ByteString.copyFrom(Arrays.copyOfRange(in, 416, 448))));
    builder.setK(BN128G1.newBuilder().setX(ByteString.copyFrom(Arrays.copyOfRange(in, 448, 480)))
        .setY(ByteString.copyFrom(Arrays.copyOfRange(in, 480, 512))));
    builder.setH(BN128G1.newBuilder().setX(ByteString.copyFrom(Arrays.copyOfRange(in, 512, 544)))
        .setY(ByteString.copyFrom(Arrays.copyOfRange(in, 544, 576))));
    return builder.build();
  }
}