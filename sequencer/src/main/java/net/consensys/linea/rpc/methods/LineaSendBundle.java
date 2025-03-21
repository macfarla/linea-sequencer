/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package net.consensys.linea.rpc.methods;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.consensys.linea.rpc.services.BundlePoolService;
import net.consensys.linea.rpc.services.LineaLimitedBundlePool;
import net.consensys.linea.rpc.services.TransactionBundle;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter;
import org.hyperledger.besu.ethereum.api.util.DomainObjectDecodeUtils;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.plugin.services.BlockchainService;
import org.hyperledger.besu.plugin.services.exception.PluginRpcEndpointException;
import org.hyperledger.besu.plugin.services.rpc.PluginRpcRequest;
import org.hyperledger.besu.plugin.services.rpc.RpcMethodError;

@Slf4j
@RequiredArgsConstructor
public class LineaSendBundle {
  private static final AtomicInteger LOG_SEQUENCE = new AtomicInteger();
  private static final int MAX_TRACKED_SEEN_REQUESTS = 1_000;
  private final JsonRpcParameter parameterParser = new JsonRpcParameter();
  private final Cache<BundleParameter, Instant> recentlySeenRequestsCache =
      Caffeine.newBuilder()
          .maximumSize(MAX_TRACKED_SEEN_REQUESTS)
          .expireAfterAccess(Duration.ofMinutes(1))
          .build();
  private final BlockchainService blockchainService;
  private BundlePoolService bundlePool;

  public LineaSendBundle init(BundlePoolService bundlePoolService) {
    this.bundlePool = bundlePoolService;
    return this;
  }

  public String getNamespace() {
    return "linea";
  }

  public String getName() {
    return "sendBundle";
  }

  public BundleResponse execute(final PluginRpcRequest request) {
    // sequence id for correlating error messages in logs:
    final int logId = log.isDebugEnabled() ? LOG_SEQUENCE.incrementAndGet() : -1;

    try {
      final BundleParameter bundleParams = parseRequest(logId, request.getParams());

      validateParameters(bundleParams);

      final var optBundleUUID = bundleParams.replacementUUID.map(UUID::fromString);

      // use replacement UUID hashed if present, otherwise the hash of the transactions themselves
      final var optBundleHash =
          optBundleUUID
              .map(LineaLimitedBundlePool::UUIDToHash)
              .or(
                  () ->
                      bundleParams.txs().stream()
                          .map(Bytes::fromHexString)
                          .reduce(Bytes::concatenate)
                          .map(Hash::hash));

      return optBundleHash
          .map(
              bundleHash -> {
                final List<Transaction> txs =
                    bundleParams.txs.stream()
                        .map(DomainObjectDecodeUtils::decodeRawTransaction)
                        .toList();

                bundlePool.putOrReplace(
                    bundleHash,
                    new TransactionBundle(
                        bundleHash,
                        txs,
                        bundleParams.blockNumber,
                        bundleParams.minTimestamp,
                        bundleParams.maxTimestamp,
                        bundleParams.revertingTxHashes(),
                        optBundleUUID));
                return new BundleResponse(bundleHash.toHexString());
              })
          .orElseThrow(
              () ->
                  // otherwise boom.
                  new RuntimeException("Malformed bundle, no bundle transactions present"));

    } catch (final Exception e) {
      throw new PluginRpcEndpointException(new LineaSendBundleError(e.getMessage()));
    }
  }

  private void validateParameters(final BundleParameter bundleParams) {
    // synchronized to avoid that 2 parallel requests with the same parameters
    // will be both processed
    synchronized (recentlySeenRequestsCache) {
      final var alreadySeenAt = recentlySeenRequestsCache.getIfPresent(bundleParams);
      if (alreadySeenAt != null) {
        throw new IllegalArgumentException(
            "request already seen " + Duration.between(alreadySeenAt, Instant.now()) + " ago");
      }
      recentlySeenRequestsCache.put(bundleParams, Instant.now());
    }

    final var chainHeadBlockNumber = blockchainService.getChainHeadHeader().getNumber();
    if (bundleParams.blockNumber <= chainHeadBlockNumber) {
      throw new IllegalArgumentException(
          "bundle block number "
              + bundleParams.blockNumber
              + " is not greater than current chain head block number "
              + chainHeadBlockNumber);
    }

    bundleParams.maxTimestamp.ifPresent(
        maxTimestamp -> {
          final var now = Instant.now().getEpochSecond();
          if (maxTimestamp < now) {
            throw new IllegalArgumentException(
                "bundle max timestamp "
                    + maxTimestamp
                    + " is in the past, current timestamp is "
                    + now);
          }
        });
  }

  private BundleParameter parseRequest(final int logId, final Object[] params) {
    try {
      BundleParameter param = parameterParser.required(params, 0, BundleParameter.class);
      return param;
    } catch (Exception e) {
      log.atError()
          .setMessage("[{}] failed to parse linea_sendBundle request")
          .addArgument(logId)
          .setCause(e)
          .log();
      throw new RuntimeException("malformed linea_sendBundle json param");
    }
  }

  public record BundleResponse(String bundleHash) {}

  static class LineaSendBundleError implements RpcMethodError {

    final String errMessage;

    LineaSendBundleError(String errMessage) {
      this.errMessage = errMessage;
    }

    @Override
    public int getCode() {
      return INVALID_PARAMS_ERROR_CODE;
    }

    @Override
    public String getMessage() {
      return errMessage;
    }
  }

  @JsonInclude(NON_ABSENT)
  @JsonPropertyOrder({"blockNumber", "minTimestamp", "maxTimestamp"})
  public record BundleParameter(
      /*  array of signed transactions to execute in a bundle */
      List<String> txs,
      /* block number for which this bundle is valid */
      Long blockNumber,
      /* Optional minimum timestamp from which this bundle is valid */
      Optional<Long> minTimestamp,
      /* Optional max timestamp for which this bundle is valid */
      Optional<Long> maxTimestamp,
      /* Optional list of transaction hashes which are allowed to revert */
      Optional<List<Hash>> revertingTxHashes,
      /* Optional UUID which can be used to replace or cancel this bundle */
      Optional<String> replacementUUID,
      /* Optional list of builders to share this bundle with */
      Optional<List<String>> builders) {
    @JsonCreator
    public BundleParameter(
        @JsonProperty("txs") final List<String> txs,
        @JsonProperty("blockNumber") final UnsignedLongParameter blockNumber,
        @JsonProperty("minTimestamp") final Optional<Long> minTimestamp,
        @JsonProperty("maxTimestamp") final Optional<Long> maxTimestamp,
        @JsonProperty("revertingTxHashes") final Optional<List<Hash>> revertingTxHashes,
        @JsonProperty("replacementUUID") final Optional<String> replacementUUID,
        @JsonProperty("builders") final Optional<List<String>> builders) {
      this(
          txs,
          blockNumber.getValue(),
          minTimestamp,
          maxTimestamp,
          revertingTxHashes,
          replacementUUID,
          builders);
    }
  }
}
