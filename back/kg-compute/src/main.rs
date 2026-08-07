use actix_web::{middleware, web, App, HttpResponse, HttpServer, Responder};
use petgraph::unionfind::UnionFind;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};
use std::env;
use std::time::Instant;

const VERSION: &str = env!("CARGO_PKG_VERSION");
const DEFAULT_HOST: &str = "0.0.0.0";
const DEFAULT_PORT: u16 = 8101;
const DEFAULT_PAGERANK_ITERATIONS: usize = 30;
const DEFAULT_PAGERANK_DAMPING: f64 = 0.85;
const LOUVAIN_MAX_PASSES: usize = 20;
const LOUVAIN_MIN_GAIN: f64 = 1e-9;

#[derive(Clone)]
struct AppConfig {
    host: String,
    port: u16,
}

impl AppConfig {
    fn from_env() -> Self {
        let host = env::var("KG_COMPUTE_HOST").unwrap_or_else(|_| DEFAULT_HOST.to_string());
        let port = env::var("KG_COMPUTE_PORT")
            .ok()
            .and_then(|value| value.parse::<u16>().ok())
            .unwrap_or(DEFAULT_PORT);

        Self { host, port }
    }

    fn bind_addr(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }
}

#[derive(Deserialize)]
struct GraphInput {
    #[serde(default)]
    algorithm: Option<String>,
    nodes: Vec<NodeData>,
    edges: Vec<EdgeData>,
}

#[derive(Deserialize)]
struct NodeData {
    id: String,
    #[allow(dead_code)]
    label: Option<String>,
}

#[derive(Deserialize)]
struct EdgeData {
    source: String,
    target: String,
    #[serde(default)]
    weight: Option<f64>,
}

#[derive(Serialize)]
struct HealthOutput {
    status: &'static str,
    service: &'static str,
    version: &'static str,
}

#[derive(Serialize)]
struct VersionOutput {
    service: &'static str,
    version: &'static str,
}

#[derive(Serialize)]
struct ComputeMetrics {
    node_count: usize,
    edge_count: usize,
    elapsed_ms: u128,
}

#[derive(Serialize)]
struct CommunitiesOutput {
    communities: Vec<Vec<String>>,
    count: usize,
    algorithm: &'static str,
    metrics: ComputeMetrics,
}

#[derive(Serialize)]
struct ScoreOutput {
    nodes: Vec<ScoredNode>,
    algorithm: &'static str,
    metrics: ComputeMetrics,
}

#[derive(Serialize)]
struct ScoredNode {
    id: String,
    score: f64,
}

#[derive(Serialize)]
struct CentralityNode {
    id: String,
    centrality: f64,
}

#[derive(Serialize)]
struct CentralityOutput {
    nodes: Vec<CentralityNode>,
    algorithm: &'static str,
    metrics: ComputeMetrics,
}

#[derive(Serialize)]
struct ErrorOutput {
    success: bool,
    error_code: &'static str,
    message: String,
}

fn log_json(level: &str, event: &str, payload: serde_json::Value) {
    println!(
        "{}",
        json!({
            "level": level,
            "event": event,
            "service": "kg-compute",
            "version": VERSION,
            "payload": payload
        })
    );
}

fn normalize_algorithm(input: Option<&str>, default: &'static str) -> Result<&'static str, String> {
    let Some(value) = input else {
        return Ok(default);
    };

    match value.trim().to_ascii_lowercase().as_str() {
        "" => Ok(default),
        "connected_components" | "union_find" => Ok("connected_components"),
        "louvain" | "modularity" => Ok("louvain"),
        "degree" | "degree_centrality" => Ok("degree_centrality"),
        "pagerank" | "page_rank" => Ok("pagerank"),
        other => Err(format!("unsupported algorithm: {other}")),
    }
}

fn node_index(nodes: &[NodeData]) -> (HashMap<String, usize>, Vec<String>) {
    let mut id_to_idx = HashMap::new();
    let mut idx_to_id = Vec::new();

    for node in nodes {
        if id_to_idx.contains_key(&node.id) {
            continue;
        }
        let idx = idx_to_id.len();
        id_to_idx.insert(node.id.clone(), idx);
        idx_to_id.push(node.id.clone());
    }

    (id_to_idx, idx_to_id)
}

fn valid_edges<'a>(
    edges: &'a [EdgeData],
    id_to_idx: &'a HashMap<String, usize>,
) -> impl Iterator<Item = (usize, usize, f64)> + 'a {
    edges.iter().filter_map(move |edge| {
        let source = *id_to_idx.get(&edge.source)?;
        let target = *id_to_idx.get(&edge.target)?;
        if source == target {
            return None;
        }
        Some((source, target, edge.weight.unwrap_or(1.0).max(0.0)))
    })
}

fn compute_connected_components(input: &GraphInput) -> Vec<Vec<String>> {
    let (id_to_idx, idx_to_id) = node_index(&input.nodes);
    let n = idx_to_id.len();
    if n == 0 {
        return Vec::new();
    }

    let mut uf = UnionFind::new(n);
    for (source, target, _) in valid_edges(&input.edges, &id_to_idx) {
        uf.union(source, target);
    }

    let mut groups: HashMap<usize, Vec<String>> = HashMap::new();
    for i in 0..n {
        let root = uf.find(i);
        groups.entry(root).or_default().push(idx_to_id[i].clone());
    }

    let mut communities: Vec<Vec<String>> = groups.into_values().collect();
    communities.sort_by(|a, b| {
        b.len()
            .cmp(&a.len())
            .then_with(|| a.first().cmp(&b.first()))
    });
    communities
}

fn compute_louvain_communities(input: &GraphInput) -> Vec<Vec<String>> {
    let (id_to_idx, idx_to_id) = node_index(&input.nodes);
    let n = idx_to_id.len();
    if n == 0 {
        return Vec::new();
    }

    let mut adjacency: Vec<HashMap<usize, f64>> = vec![HashMap::new(); n];
    let mut total_edge_weight = 0.0;
    for (source, target, weight) in valid_edges(&input.edges, &id_to_idx) {
        if weight <= 0.0 {
            continue;
        }
        *adjacency[source].entry(target).or_default() += weight;
        *adjacency[target].entry(source).or_default() += weight;
        total_edge_weight += weight;
    }

    if total_edge_weight <= 0.0 {
        return idx_to_id.into_iter().map(|id| vec![id]).collect();
    }

    let degrees: Vec<f64> = adjacency
        .iter()
        .map(|neighbors| neighbors.values().sum())
        .collect();
    let mut communities: Vec<usize> = (0..n).collect();
    let mut community_totals = degrees.clone();
    let two_m = 2.0 * total_edge_weight;

    for _ in 0..LOUVAIN_MAX_PASSES {
        let mut moved = false;

        for node in 0..n {
            let degree = degrees[node];
            if degree <= 0.0 {
                continue;
            }

            let original_community = communities[node];
            community_totals[original_community] -= degree;

            let mut neighbor_community_weights: HashMap<usize, f64> = HashMap::new();
            for (neighbor, weight) in &adjacency[node] {
                let community = communities[*neighbor];
                *neighbor_community_weights.entry(community).or_default() += *weight;
            }

            let mut best_community = original_community;
            let mut best_gain = 0.0;
            for (community, weight_to_community) in neighbor_community_weights {
                let gain = weight_to_community - (community_totals[community] * degree / two_m);
                if gain > best_gain + LOUVAIN_MIN_GAIN {
                    best_gain = gain;
                    best_community = community;
                }
            }

            community_totals[best_community] += degree;
            if best_community != original_community {
                communities[node] = best_community;
                moved = true;
            }
        }

        if !moved {
            break;
        }
    }

    let mut groups: HashMap<usize, Vec<String>> = HashMap::new();
    for (idx, community) in communities.into_iter().enumerate() {
        groups
            .entry(community)
            .or_default()
            .push(idx_to_id[idx].clone());
    }

    let mut result: Vec<Vec<String>> = groups.into_values().collect();
    result.sort_by(|a, b| {
        b.len()
            .cmp(&a.len())
            .then_with(|| a.first().cmp(&b.first()))
    });
    result
}

fn compute_degree_centrality(input: &GraphInput) -> Vec<CentralityNode> {
    let (id_to_idx, idx_to_id) = node_index(&input.nodes);
    let n = idx_to_id.len();
    if n == 0 {
        return Vec::new();
    }

    let mut neighbors: Vec<HashSet<usize>> = vec![HashSet::new(); n];
    for (source, target, _) in valid_edges(&input.edges, &id_to_idx) {
        neighbors[source].insert(target);
        neighbors[target].insert(source);
    }

    let norm = if n > 1 { (n - 1) as f64 } else { 1.0 };
    let mut nodes: Vec<CentralityNode> = idx_to_id
        .into_iter()
        .enumerate()
        .map(|(idx, id)| CentralityNode {
            id,
            centrality: neighbors[idx].len() as f64 / norm,
        })
        .collect();

    nodes.sort_by(|a, b| {
        b.centrality
            .partial_cmp(&a.centrality)
            .unwrap_or(Ordering::Equal)
            .then_with(|| a.id.cmp(&b.id))
    });
    nodes
}

fn compute_pagerank(input: &GraphInput, iterations: usize, damping: f64) -> Vec<ScoredNode> {
    let (id_to_idx, idx_to_id) = node_index(&input.nodes);
    let n = idx_to_id.len();
    if n == 0 {
        return Vec::new();
    }

    let mut neighbors: Vec<HashSet<usize>> = vec![HashSet::new(); n];
    for (source, target, _) in valid_edges(&input.edges, &id_to_idx) {
        neighbors[source].insert(target);
        neighbors[target].insert(source);
    }

    let mut scores = vec![1.0 / n as f64; n];
    let teleport = (1.0 - damping) / n as f64;

    for _ in 0..iterations {
        let mut next = vec![teleport; n];
        let mut dangling_score = 0.0;

        for (idx, current_score) in scores.iter().enumerate() {
            if neighbors[idx].is_empty() {
                dangling_score += current_score / n as f64;
                continue;
            }

            let share = current_score / neighbors[idx].len() as f64;
            for neighbor in &neighbors[idx] {
                next[*neighbor] += damping * share;
            }
        }

        if dangling_score > 0.0 {
            for value in &mut next {
                *value += damping * dangling_score;
            }
        }

        scores = next;
    }

    let mut nodes: Vec<ScoredNode> = idx_to_id
        .into_iter()
        .enumerate()
        .map(|(idx, id)| ScoredNode {
            id,
            score: scores[idx],
        })
        .collect();

    nodes.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(Ordering::Equal)
            .then_with(|| a.id.cmp(&b.id))
    });
    nodes
}

fn metrics(input: &GraphInput, elapsed_ms: u128) -> ComputeMetrics {
    ComputeMetrics {
        node_count: input.nodes.len(),
        edge_count: input.edges.len(),
        elapsed_ms,
    }
}

fn bad_request(message: String) -> HttpResponse {
    HttpResponse::BadRequest().json(ErrorOutput {
        success: false,
        error_code: "UNSUPPORTED_ALGORITHM",
        message,
    })
}

async fn health() -> impl Responder {
    HttpResponse::Ok().json(HealthOutput {
        status: "UP",
        service: "kg-compute",
        version: VERSION,
    })
}

async fn version() -> impl Responder {
    HttpResponse::Ok().json(VersionOutput {
        service: "kg-compute",
        version: VERSION,
    })
}

async fn compute_communities(body: web::Json<GraphInput>) -> HttpResponse {
    let input = body.into_inner();
    let algorithm = match normalize_algorithm(input.algorithm.as_deref(), "connected_components") {
        Ok("connected_components") => "connected_components",
        Ok("louvain") => "louvain",
        Ok(other) => {
            return bad_request(format!("{other} is not supported by /compute/communities"))
        }
        Err(err) => return bad_request(err),
    };

    let started = Instant::now();
    let communities = match algorithm {
        "louvain" => compute_louvain_communities(&input),
        _ => compute_connected_components(&input),
    };
    let elapsed_ms = started.elapsed().as_millis();

    log_json(
        "INFO",
        "compute_communities",
        json!({
            "algorithm": algorithm,
            "node_count": input.nodes.len(),
            "edge_count": input.edges.len(),
            "community_count": communities.len(),
            "elapsed_ms": elapsed_ms
        }),
    );

    HttpResponse::Ok().json(CommunitiesOutput {
        count: communities.len(),
        communities,
        algorithm,
        metrics: metrics(&input, elapsed_ms),
    })
}

async fn compute_centrality(body: web::Json<GraphInput>) -> HttpResponse {
    let input = body.into_inner();
    let algorithm = match normalize_algorithm(input.algorithm.as_deref(), "degree_centrality") {
        Ok("degree_centrality") => "degree_centrality",
        Ok(other) => {
            return bad_request(format!("{other} is not supported by /compute/centrality"))
        }
        Err(err) => return bad_request(err),
    };

    let started = Instant::now();
    let nodes = compute_degree_centrality(&input);
    let elapsed_ms = started.elapsed().as_millis();

    log_json(
        "INFO",
        "compute_centrality",
        json!({
            "algorithm": algorithm,
            "node_count": input.nodes.len(),
            "edge_count": input.edges.len(),
            "elapsed_ms": elapsed_ms
        }),
    );

    HttpResponse::Ok().json(CentralityOutput {
        nodes,
        algorithm,
        metrics: metrics(&input, elapsed_ms),
    })
}

async fn compute_pagerank_handler(body: web::Json<GraphInput>) -> HttpResponse {
    let input = body.into_inner();
    let algorithm = match normalize_algorithm(input.algorithm.as_deref(), "pagerank") {
        Ok("pagerank") => "pagerank",
        Ok(other) => return bad_request(format!("{other} is not supported by /compute/pagerank")),
        Err(err) => return bad_request(err),
    };

    let started = Instant::now();
    let nodes = compute_pagerank(
        &input,
        DEFAULT_PAGERANK_ITERATIONS,
        DEFAULT_PAGERANK_DAMPING,
    );
    let elapsed_ms = started.elapsed().as_millis();

    log_json(
        "INFO",
        "compute_pagerank",
        json!({
            "algorithm": algorithm,
            "node_count": input.nodes.len(),
            "edge_count": input.edges.len(),
            "iterations": DEFAULT_PAGERANK_ITERATIONS,
            "damping": DEFAULT_PAGERANK_DAMPING,
            "elapsed_ms": elapsed_ms
        }),
    );

    HttpResponse::Ok().json(ScoreOutput {
        nodes,
        algorithm,
        metrics: metrics(&input, elapsed_ms),
    })
}

fn app_config(cfg: &mut web::ServiceConfig) {
    cfg.route("/health", web::get().to(health))
        .route("/version", web::get().to(version))
        .route("/compute/communities", web::post().to(compute_communities))
        .route("/compute/centrality", web::post().to(compute_centrality))
        .route(
            "/compute/pagerank",
            web::post().to(compute_pagerank_handler),
        );
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let config = AppConfig::from_env();
    let bind_addr = config.bind_addr();

    log_json(
        "INFO",
        "startup",
        json!({
            "bind_addr": bind_addr,
            "host": config.host,
            "port": config.port
        }),
    );

    HttpServer::new(|| {
        App::new()
            .wrap(middleware::Logger::default())
            .configure(app_config)
    })
    .bind(&bind_addr)?
    .run()
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use actix_web::{http::StatusCode, test as actix_test};

    fn node(id: &str) -> NodeData {
        NodeData {
            id: id.to_string(),
            label: None,
        }
    }

    fn edge(source: &str, target: &str) -> EdgeData {
        EdgeData {
            source: source.to_string(),
            target: target.to_string(),
            weight: None,
        }
    }

    fn graph(nodes: Vec<NodeData>, edges: Vec<EdgeData>) -> GraphInput {
        GraphInput {
            algorithm: None,
            nodes,
            edges,
        }
    }

    #[test]
    fn connected_components_handles_empty_graph() {
        let input = graph(Vec::new(), Vec::new());

        let communities = compute_connected_components(&input);

        assert!(communities.is_empty());
    }

    #[test]
    fn connected_components_groups_disconnected_subgraphs() {
        let input = graph(
            vec![node("a"), node("b"), node("c"), node("d")],
            vec![edge("a", "b"), edge("c", "d")],
        );

        let communities = compute_connected_components(&input);

        assert_eq!(communities.len(), 2);
        assert!(communities
            .iter()
            .any(|group| group == &vec!["a".to_string(), "b".to_string()]));
        assert!(communities
            .iter()
            .any(|group| group == &vec!["c".to_string(), "d".to_string()]));
    }

    #[test]
    fn louvain_separates_dense_clusters_with_bridge() {
        let input = graph(
            vec![
                node("a"),
                node("b"),
                node("c"),
                node("d"),
                node("e"),
                node("f"),
            ],
            vec![
                edge("a", "b"),
                edge("a", "c"),
                edge("b", "c"),
                edge("d", "e"),
                edge("d", "f"),
                edge("e", "f"),
                edge("c", "d"),
            ],
        );

        let communities = compute_louvain_communities(&input);

        assert_eq!(communities.len(), 2);
        assert!(communities
            .iter()
            .any(|group| group == &vec!["a".to_string(), "b".to_string(), "c".to_string()]));
        assert!(communities
            .iter()
            .any(|group| group == &vec!["d".to_string(), "e".to_string(), "f".to_string()]));
    }

    #[test]
    fn degree_centrality_ignores_edges_with_unknown_nodes() {
        let input = graph(
            vec![node("a"), node("b"), node("c")],
            vec![edge("a", "b"), edge("a", "missing")],
        );

        let nodes = compute_degree_centrality(&input);

        assert_eq!(nodes[0].id, "a");
        assert_eq!(nodes[0].centrality, 0.5);
        assert!(nodes
            .iter()
            .any(|item| item.id == "c" && item.centrality == 0.0));
    }

    #[test]
    fn pagerank_scores_sum_to_one() {
        let input = graph(
            vec![node("a"), node("b"), node("c")],
            vec![edge("a", "b"), edge("b", "c")],
        );

        let nodes = compute_pagerank(&input, 20, 0.85);
        let total: f64 = nodes.iter().map(|item| item.score).sum();

        assert!((total - 1.0).abs() < 0.000001);
    }

    #[actix_web::test]
    async fn health_endpoint_returns_up() {
        let app = actix_test::init_service(App::new().configure(app_config)).await;
        let req = actix_test::TestRequest::get().uri("/health").to_request();

        let resp = actix_test::call_service(&app, req).await;

        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[actix_web::test]
    async fn communities_endpoint_keeps_legacy_shape() {
        let app = actix_test::init_service(App::new().configure(app_config)).await;
        let req = actix_test::TestRequest::post()
            .uri("/compute/communities")
            .set_json(json!({
                "nodes": [{"id": "1"}, {"id": "2"}],
                "edges": [{"source": "1", "target": "2"}]
            }))
            .to_request();

        let body: serde_json::Value = actix_test::call_and_read_body_json(&app, req).await;

        assert_eq!(body["count"], 1);
        assert!(body["communities"].is_array());
        assert_eq!(body["algorithm"], "connected_components");
    }
}
